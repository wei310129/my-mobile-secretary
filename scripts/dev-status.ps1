<#
.SYNOPSIS
  Local health report for the development environment; LINE platform probing is explicit opt-in.

.PARAMETER NoNgrokRequired
  Does not fail when ngrok is stopped.

.PARAMETER RequireDispatcher
  Fails when the Dispatcher DB or application is unhealthy. It is non-blocking by default.

.PARAMETER SkipLineWebhookTest
  Compatibility switch. LINE webhook probing is already skipped unless -ExternalLineProbe is supplied.

.PARAMETER ExternalLineProbe
  Explicitly runs LINE's official end-to-end webhook test after local status collection.

.PARAMETER VerboseOutput
  Prints the per-layer status details even when every required layer is healthy.
#>
param(
    [switch]$NoNgrokRequired,
    [switch]$RequireDispatcher,
    [switch]$SkipLineWebhookTest,
    [switch]$ExternalLineProbe,
    [switch]$VerboseOutput
)

. "$PSScriptRoot\_devops-common.ps1"
. "$PSScriptRoot\environment-common.ps1"
$state = Read-DevState

$statusDetails = [System.Collections.Generic.List[object]]::new()
function Add-StatusDetail {
    param([Parameter(Mandatory)][string]$Message, [string]$ForegroundColor)
    $statusDetails.Add([pscustomobject]@{ Message = $Message; Color = $ForegroundColor })
}
function Show-StatusDetails {
    foreach ($detail in $statusDetails) {
        if ($detail.Color) { Write-Host $detail.Message -ForegroundColor $detail.Color }
        else { Write-Host $detail.Message }
    }
}
function Get-DevStateValue {
    param([Parameter(Mandatory)]$State, [Parameter(Mandatory)][string]$Name)
    $property = $State.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

$allHealthy = $true
$environmentContext = Get-EnvironmentStateContext -RepoRoot $RepoRoot
$environmentSnapshot = Read-EnvironmentJson -Path $environmentContext.SnapshotPath
$environmentFresh = Test-EnvironmentSnapshotFresh -Snapshot $environmentSnapshot
$environmentReady = $environmentSnapshot -and [bool]$environmentSnapshot.capability.Ready
if ($environmentSnapshot) {
    $environmentDisplay = "state=$($environmentSnapshot.capability.State), capability=$($environmentSnapshot.requestedCapability), caller=$($environmentSnapshot.caller.Kind), fresh=$environmentFresh"
    Add-StatusDetail -Message "Environment:    $environmentDisplay" -ForegroundColor $(if($environmentFresh -and $environmentReady){'Green'}else{'Yellow'})
} else {
    Add-StatusDetail -Message 'Environment:    snapshot missing; run dev-preflight.ps1' -ForegroundColor Yellow
}
if (-not $environmentFresh -or -not $environmentReady) { $allHealthy = $false }
$dockerProbe = Get-EnvironmentDockerProbe -RequireDaemon
$dockerAvailable = [bool]$dockerProbe.Ready
$dockerCallerBlocked = $dockerProbe.State -eq 'HOST_READY_CALLER_BLOCKED'
$dockerUnknown = $dockerProbe.State -eq 'UNKNOWN'
Add-StatusDetail -Message ("Docker:         state={0}, cli={1}, daemon={2}" -f $dockerProbe.State,$dockerProbe.CliReady,$dockerProbe.DaemonReady) `
    -ForegroundColor $(if($dockerAvailable){'Green'}elseif($dockerCallerBlocked -or $dockerUnknown){'Yellow'}else{'Red'})
$checkoutVersion = Get-CheckoutServiceVersion
$runningVersion = $null
$versionComparison = $null

# Required main infrastructure.
$pgStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-postgres" } else { $null }
$redisStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-redis" } else { $null }
$pgDisplay = if ($pgStatus) { $pgStatus } else { "not running" }
$redisDisplay = if ($redisStatus) { $redisStatus } else { "not running" }
Add-StatusDetail -Message "Postgres:       $pgDisplay"
Add-StatusDetail -Message "Redis:          $redisDisplay"
if ($pgStatus -ne "healthy" -or $redisStatus -ne "healthy") { $allHealthy = $false }

# Required main application.
$appPortPid = Get-PortOwnerPid -Port $AppPort
if ($appPortPid) {
    $health = try { (Invoke-RestMethod -Uri "http://localhost:$AppPort/actuator/health" -TimeoutSec 3).status } catch { "no response" }
    $managed = Test-ManagedProcess -ProcessId $appPortPid -Kind "SpringBoot"
    $runningVersion = Get-RunningServiceVersion
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    $color = if ($health -eq "UP" -and $managed -and $versionComparison.Status -eq "CURRENT") { "Green" } else { "Yellow" }
    Add-StatusDetail -Message "Spring Boot:    running (PID $appPortPid, health=$health, managed=$managed)" -ForegroundColor $color
    $runningLabel = if ($runningVersion.Available) { $runningVersion.VersionLabel } else { "unavailable" }
    Add-StatusDetail -Message "  running version: $runningLabel ($($versionComparison.Status))" -ForegroundColor $color
    if ($runningVersion.Available) {
        Add-StatusDetail -Message "  running SHA:     $($runningVersion.GitSha)" -ForegroundColor DarkGray
        Add-StatusDetail -Message "  started at:      $($runningVersion.StartedAt)" -ForegroundColor DarkGray
        Add-StatusDetail -Message "  change:          $($runningVersion.ChangeSummary)" -ForegroundColor DarkGray
    } elseif ($runningVersion.Error) {
        Add-StatusDetail -Message "  version error:   $($runningVersion.Error)" -ForegroundColor Yellow
    }
    if ($health -ne "UP" -or -not $managed -or $versionComparison.Status -ne "CURRENT") { $allHealthy = $false }
} else {
    Add-StatusDetail -Message "Spring Boot:    not running" -ForegroundColor DarkGray
    $allHealthy = $false
}
$checkoutLabel = if ($checkoutVersion.Available) { $checkoutVersion.VersionLabel } else { "unavailable" }
Add-StatusDetail -Message "Checkout:       $checkoutLabel" -ForegroundColor DarkGray
if ($checkoutVersion.Available) {
    Add-StatusDetail -Message "  checkout SHA:    $($checkoutVersion.GitSha)" -ForegroundColor DarkGray
} elseif ($checkoutVersion.Error) {
    Add-StatusDetail -Message "  checkout error:  $($checkoutVersion.Error)" -ForegroundColor Yellow
}

# Dispatcher is isolated and affects the exit code only when explicitly required.
$dispatcherDbStatus = if ($dockerAvailable) {
    Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres"
} else { $null }
$dispatcherDbHealthy = $dispatcherDbStatus -eq "healthy"
$dispatcherDbDisplay = if ($dispatcherDbStatus) { $dispatcherDbStatus } else { "not running" }
Add-StatusDetail -Message "Dispatcher DB:  $dispatcherDbDisplay"
$dispatcherLaneState = if ($dispatcherDbHealthy) { Get-DispatcherLaneState } else { $null }
$dispatcherLaneDisplay = if ($dispatcherLaneState) { $dispatcherLaneState } else { "unknown" }
$dispatcherLaneColor = if (Test-DispatcherLaneActive -State $dispatcherLaneState) {
    "Yellow"
} elseif ($dispatcherLaneState -eq "PAUSED") {
    "Yellow"
} else {
    "DarkGray"
}
Add-StatusDetail -Message "Dispatcher lane: $dispatcherLaneDisplay" -ForegroundColor $dispatcherLaneColor
$dispatcherArmed = [bool](Get-DevStateValue -State $state -Name 'dispatcherArmed')
$dispatcherMode = if ($dispatcherArmed) { "ARMED" } else { "DISARMED" }
$dispatcherModeColor = if ($dispatcherArmed) { "Yellow" } else { "DarkGray" }
Add-StatusDetail -Message "Dispatcher mode: $dispatcherMode (last managed start)" -ForegroundColor $dispatcherModeColor

$dispatcherPortPid = Get-PortOwnerPid -Port $DispatcherPort
$dispatcherHealthy = $false
if ($dispatcherPortPid) {
    $dispatcherHealth = try {
        (Invoke-RestMethod -Uri "http://localhost:$DispatcherPort/actuator/health" -TimeoutSec 3).status
    } catch { "no response" }
    $dispatcherManaged = Test-ManagedProcess -ProcessId $dispatcherPortPid -Kind "Dispatcher"
    $dispatcherHealthy = $dispatcherHealth -eq "UP" -and $dispatcherManaged
    $dispatcherColor = if ($dispatcherHealthy) { "Green" } else { "Yellow" }
    Add-StatusDetail -Message "AI Dispatcher: running (PID $dispatcherPortPid, health=$dispatcherHealth, managed=$dispatcherManaged)" `
        -ForegroundColor $dispatcherColor
} else {
    Add-StatusDetail -Message "AI Dispatcher: not running (main application is unaffected)" -ForegroundColor DarkGray
}
if ($RequireDispatcher -and (-not $dispatcherDbHealthy -or -not $dispatcherHealthy)) {
    $allHealthy = $false
}

# ngrok requirement is controlled independently.
$ngrokPortPid = Get-PortOwnerPid -Port $NgrokApiPort
if ($ngrokPortPid) {
    $url = Get-NgrokPublicUrl -TimeoutSec 5
    Add-StatusDetail -Message "ngrok:          running (PID $ngrokPortPid)" -ForegroundColor Green
    if ($url) { Add-StatusDetail -Message "  webhook:      $url/api/line/webhook" }
} else {
    Add-StatusDetail -Message "ngrok:          not running" -ForegroundColor DarkGray
    if (-not $NoNgrokRequired) { $allHealthy = $false }
}

if ($ExternalLineProbe -and -not $SkipLineWebhookTest -and -not $NoNgrokRequired) {
    Add-StatusDetail -Message "LINE webhook:   official end-to-end test executed" -ForegroundColor DarkGray
    $lineTest = Test-LineWebhookEndToEnd
    if ($lineTest.Success) {
        Add-StatusDetail -Message "LINE webhook:   connected (LINE -> ngrok -> Spring Boot)" -ForegroundColor Green
    } else {
        Add-StatusDetail -Message "LINE webhook:   disconnected" -ForegroundColor Red
        if ($lineTest.Reason) { Add-StatusDetail -Message "  reason:       $($lineTest.Reason)" -ForegroundColor Yellow }
        if ($lineTest.Detail) { Add-StatusDetail -Message "  detail:       $($lineTest.Detail)" -ForegroundColor Yellow }
        if ($lineTest.Error) { Add-StatusDetail -Message "  error:        $($lineTest.Error)" -ForegroundColor Yellow }
        $allHealthy = $false
    }
} else {
    Add-StatusDetail -Message "LINE webhook:   external probe skipped (use -ExternalLineProbe to enable)" -ForegroundColor DarkGray
}

${startedAt} = Get-DevStateValue -State $state -Name 'startedAt'
if ($startedAt) {
    Add-StatusDetail -Message "Last dev-start.ps1 time: $startedAt" -ForegroundColor DarkGray
}
$serviceGeneration = Get-DevStateValue -State $state -Name 'serviceGeneration'
if ($serviceGeneration) {
    Add-StatusDetail -Message "Service generation: $serviceGeneration" -ForegroundColor DarkGray
    $serviceLogDirectory = Get-DevStateValue -State $state -Name 'serviceLogDirectory'
    if ($serviceLogDirectory) { Add-StatusDetail -Message "  logs:          $serviceLogDirectory" -ForegroundColor DarkGray }
}
if (-not $RequireDispatcher) {
    Add-StatusDetail -Message "Dispatcher is failure-isolated. Use -RequireDispatcher for strict checking." -ForegroundColor DarkGray
}

if ($VerboseOutput -or -not $allHealthy) { Show-StatusDetails }
if (-not $allHealthy) {
    Write-Host "Development environment unhealthy; inspect the layer details above." -ForegroundColor Red
    exit 1
}
$dispatcherSummary = if ($dispatcherHealthy) { "dispatcher=healthy" } else { "dispatcher=optional-unavailable" }
$lineSummary = if ($ExternalLineProbe -and -not $SkipLineWebhookTest -and -not $NoNgrokRequired) { "LINE=connected" } else { "LINE=skipped" }
$versionSummary = if ($runningVersion) { $runningVersion.VersionLabel } else { "unknown" }
Write-Host "Development environment healthy: main=UP; version=$versionSummary; source=CURRENT; Postgres=healthy; Redis=healthy; $dispatcherSummary; $lineSummary." -ForegroundColor Green
exit 0

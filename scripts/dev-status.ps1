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
$mainSharedStatus = Get-SharedInfrastructureStatus -Name @('main-postgres','main-redis') -DockerStabilityWindowSeconds 1
$infrastructureObservation = Get-DevInfrastructureObservation -Result $mainSharedStatus
$dockerAvailable = $infrastructureObservation.State -eq 'UP'
$sharedEntries = @{}
foreach ($entry in @($mainSharedStatus.Containers)) { $sharedEntries[$entry.Contract.Name] = $entry }
$dockerClassification = if ($mainSharedStatus.Classification) { $mainSharedStatus.Classification } else { 'DAEMON_NOT_READY' }
if (-not $dockerAvailable) {
    Add-StatusDetail -Message "Docker:         $($infrastructureObservation.State) ($dockerClassification)" -ForegroundColor Yellow
}
$checkoutVersion = Get-CheckoutServiceVersion
$runningVersion = $null
$versionComparison = $null

# Required main infrastructure.
$pgEntry = $sharedEntries['main-postgres']
$redisEntry = $sharedEntries['main-redis']
$pgStatus = if ($dockerAvailable -and $pgEntry) { $pgEntry.Health } else { $null }
$redisStatus = if ($dockerAvailable -and $redisEntry) { $redisEntry.Health } else { $null }
$pgDisplay = if ($pgStatus) { $pgStatus } elseif ($infrastructureObservation.State -eq 'DOWN') { 'not running (DOWN)' } else { $infrastructureObservation.State }
$redisDisplay = if ($redisStatus) { $redisStatus } elseif ($infrastructureObservation.State -eq 'DOWN') { 'not running (DOWN)' } else { $infrastructureObservation.State }
Add-StatusDetail -Message "Postgres:       $pgDisplay"
Add-StatusDetail -Message "Redis:          $redisDisplay"
if ($pgStatus -ne "healthy" -or $redisStatus -ne "healthy") { $allHealthy = $false }

# Required main application. The durable launcher PID is authoritative; a port owner can be its Java child.
$trackedAppPid = Get-DevStateValue -State $state -Name 'springBootPid'
$appQuery = if ($trackedAppPid) { Get-ManagedProcessQueryResult -ProcessId ([int]$trackedAppPid) } else { $null }
$appObservation = if ($trackedAppPid) {
    Get-ManagedProcessObservation -QueryResult $appQuery
} else {
    $portObservation = Get-DevPortObservation -Port $AppPort
    if ($portObservation.State -eq 'DOWN') { [pscustomobject]@{State='DOWN';ReasonCode='NO_TRACKED_PROCESS_OR_LISTENER'} }
    elseif ($portObservation.State -eq 'CALLER_ACCESS_DENIED') { [pscustomobject]@{State='CALLER_ACCESS_DENIED';ReasonCode=$portObservation.ReasonCode} }
    else { [pscustomobject]@{State='UNKNOWN';ReasonCode='UNTRACKED_OR_UNOBSERVABLE_LISTENER'} }
}
if ($appObservation.State -eq 'UP') {
    $appPortPid = [int]$trackedAppPid
    $health = try { (Invoke-RestMethod -Uri "http://localhost:$AppPort/actuator/health" -TimeoutSec 3).status } catch { "no response" }
    $managed = try { Test-ManagedProcessCommand -Snapshot $appQuery.Snapshot -Worktree $RepoRoot -Component SpringBoot -ExactActiveCoordinationProof } catch { $false }
    $runningVersion = Get-RunningServiceVersion
    $runtimeIdentity = Get-DevStateValue -State $state -Name 'runtimeContentIdentity'
    $runningVersion = Set-ServiceVersionContentIdentity -Version $runningVersion -Identity $runtimeIdentity
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    $verifiedSource = $versionComparison.Status -in @('CURRENT','VERIFIED_DIRTY')
    if ($verifiedSource) {
        $verifiedSource = Test-RuntimeContentGeneration -Identity $runtimeIdentity -ServiceGeneration ([string](Get-DevStateValue -State $state -Name 'serviceGeneration'))
        if (-not $verifiedSource) { $versionComparison = [pscustomobject]@{Status='STALE';Reason='runtime content receipt generation does not match the active service generation'} }
    }
    $color = if ($health -eq "UP" -and $managed -and $verifiedSource) { "Green" } else { "Yellow" }
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
    if ($health -ne "UP" -or -not $managed -or -not $verifiedSource) { $allHealthy = $false }
    $springOwnership = Get-ManagedOrphanDiagnosis -Worktree $RepoRoot -Component SpringBoot -State $state
    if ($springOwnership.Classification -like 'ORPHAN_*') {
        Add-StatusDetail -Message "  ownership:      $($springOwnership.Classification) ($($springOwnership.ReasonCode))" -ForegroundColor Yellow
        if ($springOwnership.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') { $allHealthy = $false }
    }
} else {
    $springDisplay = if ($appObservation.State -eq 'DOWN') { 'not running (DOWN)' } else { $appObservation.State }
    Add-StatusDetail -Message "Spring Boot:    $springDisplay" -ForegroundColor $(if($appObservation.State -eq 'DOWN'){'DarkGray'}else{'Yellow'})
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
$dispatcherContract = Get-SharedInfrastructureContracts -Name @('dispatcher-postgres') | Select-Object -First 1
$dispatcherEntry = if ($dockerAvailable) { Get-SharedContainerStatus -Contract $dispatcherContract } else { $null }
$dispatcherDbStatus = if ($dockerAvailable -and $dispatcherEntry -and $dispatcherEntry.Health) { $dispatcherEntry.Health } else { $null }
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

# ngrok requirement is controlled independently and uses the durable tracked PID.
$trackedNgrokPid = Get-DevStateValue -State $state -Name 'ngrokPid'
$ngrokQuery = if ($trackedNgrokPid) { Get-ManagedProcessQueryResult -ProcessId ([int]$trackedNgrokPid) } else { $null }
$ngrokObservation = if ($trackedNgrokPid) {
    Get-ManagedProcessObservation -QueryResult $ngrokQuery
} else {
    $ngrokPortObservation = Get-DevPortObservation -Port $NgrokApiPort
    if ($ngrokPortObservation.State -eq 'DOWN') { [pscustomobject]@{State='DOWN'} }
    elseif ($ngrokPortObservation.State -eq 'CALLER_ACCESS_DENIED') { [pscustomobject]@{State='CALLER_ACCESS_DENIED'} }
    else { [pscustomobject]@{State='UNKNOWN'} }
}
if ($ngrokObservation.State -eq 'UP') {
    $ngrokPortPid = [int]$trackedNgrokPid
    $url = Get-NgrokPublicUrl -TimeoutSec 5
    Add-StatusDetail -Message "ngrok:          running (PID $ngrokPortPid)" -ForegroundColor Green
    if ($url) { Add-StatusDetail -Message "  webhook:      configured HTTPS tunnel (endpoint redacted)" }
    $ngrokOwnership = Get-ManagedOrphanDiagnosis -Worktree $RepoRoot -Component Ngrok -State $state
    if ($ngrokOwnership.Classification -like 'ORPHAN_*') {
        Add-StatusDetail -Message "  ownership:     $($ngrokOwnership.Classification) ($($ngrokOwnership.ReasonCode))" -ForegroundColor Yellow
        if ($ngrokOwnership.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') { $allHealthy = $false }
    }
} else {
    $ngrokDisplay = if ($ngrokObservation.State -eq 'DOWN') { 'not running (DOWN)' } else { $ngrokObservation.State }
    Add-StatusDetail -Message "ngrok:          $ngrokDisplay" -ForegroundColor $(if($ngrokObservation.State -eq 'DOWN'){'DarkGray'}else{'Yellow'})
    if (-not $NoNgrokRequired) { $allHealthy = $false }
}

$lineProbeStatus=Invoke-DevExternalLineProbe -Requested ([bool]$ExternalLineProbe) -Skipped ([bool]$SkipLineWebhookTest) -NoNgrokRequired ([bool]$NoNgrokRequired)
if ($lineProbeStatus.Required) {
    Add-StatusDetail -Message "LINE webhook:   official end-to-end test executed" -ForegroundColor DarkGray
    $lineTest = $lineProbeStatus.Result
    if ($lineTest.Success) {
        Add-StatusDetail -Message "LINE webhook:   connected (LINE -> ngrok -> Spring Boot)" -ForegroundColor Green
        $probeGeneration=[string](Get-DevStateValue -State $state -Name 'serviceGeneration')
        if([string]::IsNullOrWhiteSpace($probeGeneration)){throw 'LINE managed operation completed without a service generation fence'}
        Publish-EnvironmentManagedOperationReceipt -Capability LINE_E2E -Operation LINE_CONNECTIVITY_PROBE `
            -Generation $probeGeneration -RepoRoot $RepoRoot|Out-Null
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
$statusExitCode=Get-DevStatusExitCode -AllHealthy $allHealthy
if ($statusExitCode -ne 0) {
    Write-Host "Development environment unhealthy; inspect the layer details above." -ForegroundColor Red
    exit $statusExitCode
}
$dispatcherSummary = if ($dispatcherHealthy) { "dispatcher=healthy" } else { "dispatcher=optional-unavailable" }
$lineSummary = if ($ExternalLineProbe -and -not $SkipLineWebhookTest -and -not $NoNgrokRequired) { "LINE=connected" } else { "LINE=skipped" }
$versionSummary = if ($runningVersion) { $runningVersion.VersionLabel } else { "unknown" }
$sourceSummary = if($versionComparison){$versionComparison.Status}else{'UNKNOWN'}
Write-Host "Development environment healthy: main=UP; version=$versionSummary; source=$sourceSummary; Postgres=healthy; Redis=healthy; $dispatcherSummary; $lineSummary." -ForegroundColor Green
exit 0

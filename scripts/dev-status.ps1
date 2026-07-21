<#
.SYNOPSIS
  Health report for the full development environment, including a LINE platform webhook test.

.PARAMETER NoNgrokRequired
  Does not fail when ngrok is stopped.

.PARAMETER RequireDispatcher
  Fails when the Dispatcher DB or application is unhealthy. It is non-blocking by default.

.PARAMETER SkipLineWebhookTest
  Skips LINE's official end-to-end webhook test and reports local layers only.

.PARAMETER VerboseOutput
  Prints the per-layer status details even when every required layer is healthy.
#>
param(
    [switch]$NoNgrokRequired,
    [switch]$RequireDispatcher,
    [switch]$SkipLineWebhookTest,
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

$allHealthy = $true
$dockerAvailable = (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-DockerDaemon)

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
    $color = if ($health -eq "UP" -and $managed) { "Green" } else { "Yellow" }
    Add-StatusDetail -Message "Spring Boot:    running (PID $appPortPid, health=$health, managed=$managed)" -ForegroundColor $color
    if ($health -ne "UP" -or -not $managed) { $allHealthy = $false }
} else {
    Add-StatusDetail -Message "Spring Boot:    not running" -ForegroundColor DarkGray
    $allHealthy = $false
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
$dispatcherMode = if ([bool]$state.dispatcherArmed) { "ARMED" } else { "DISARMED" }
$dispatcherModeColor = if ([bool]$state.dispatcherArmed) { "Yellow" } else { "DarkGray" }
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

if (-not $SkipLineWebhookTest -and -not $NoNgrokRequired) {
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
} elseif ($SkipLineWebhookTest) {
    Add-StatusDetail -Message "LINE webhook:   official test skipped (-SkipLineWebhookTest)" -ForegroundColor DarkGray
}

if ($state.startedAt) {
    Add-StatusDetail -Message "Last dev-start.ps1 time: $($state.startedAt)" -ForegroundColor DarkGray
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
$lineSummary = if ($SkipLineWebhookTest -or $NoNgrokRequired) { "LINE=skipped" } else { "LINE=connected" }
Write-Host "Development environment healthy: main=UP; Postgres=healthy; Redis=healthy; $dispatcherSummary; $lineSummary." -ForegroundColor Green
exit 0

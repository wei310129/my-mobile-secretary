<#
.SYNOPSIS
  Health report for the full development environment, including a LINE platform webhook test.

.PARAMETER NoNgrokRequired
  Does not fail when ngrok is stopped.

.PARAMETER RequireDispatcher
  Fails when the Dispatcher DB or application is unhealthy. It is non-blocking by default.

.PARAMETER SkipLineWebhookTest
  Skips LINE's official end-to-end webhook test and reports local layers only.
#>
param(
    [switch]$NoNgrokRequired,
    [switch]$RequireDispatcher,
    [switch]$SkipLineWebhookTest
)

. "$PSScriptRoot\_devops-common.ps1"
$state = Read-DevState

Write-Host "=== Development environment status ===" -ForegroundColor Cyan
$allHealthy = $true
$dockerAvailable = (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-DockerDaemon)

# Required main infrastructure.
$pgStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-postgres" } else { $null }
$redisStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-redis" } else { $null }
$pgDisplay = if ($pgStatus) { $pgStatus } else { "not running" }
$redisDisplay = if ($redisStatus) { $redisStatus } else { "not running" }
Write-Host "Postgres:       $pgDisplay"
Write-Host "Redis:          $redisDisplay"
if ($pgStatus -ne "healthy" -or $redisStatus -ne "healthy") { $allHealthy = $false }

# Required main application.
$appPortPid = Get-PortOwnerPid -Port $AppPort
if ($appPortPid) {
    $health = try { (Invoke-RestMethod -Uri "http://localhost:$AppPort/actuator/health" -TimeoutSec 3).status } catch { "no response" }
    $managed = Test-ManagedProcess -ProcessId $appPortPid -Kind "SpringBoot"
    $color = if ($health -eq "UP" -and $managed) { "Green" } else { "Yellow" }
    Write-Host "Spring Boot:    running (PID $appPortPid, health=$health, managed=$managed)" -ForegroundColor $color
    if ($health -ne "UP" -or -not $managed) { $allHealthy = $false }
} else {
    Write-Host "Spring Boot:    not running" -ForegroundColor DarkGray
    $allHealthy = $false
}

# Dispatcher is isolated and affects the exit code only when explicitly required.
$dispatcherDbStatus = if ($dockerAvailable) {
    Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres"
} else { $null }
$dispatcherDbHealthy = $dispatcherDbStatus -eq "healthy"
$dispatcherDbDisplay = if ($dispatcherDbStatus) { $dispatcherDbStatus } else { "not running" }
Write-Host "Dispatcher DB:  $dispatcherDbDisplay"
$dispatcherLaneState = if ($dispatcherDbHealthy) { Get-DispatcherLaneState } else { $null }
$dispatcherLaneDisplay = if ($dispatcherLaneState) { $dispatcherLaneState } else { "unknown" }
$dispatcherLaneColor = if (Test-DispatcherLaneActive -State $dispatcherLaneState) {
    "Yellow"
} elseif ($dispatcherLaneState -eq "PAUSED") {
    "Yellow"
} else {
    "DarkGray"
}
Write-Host "Dispatcher lane: $dispatcherLaneDisplay" -ForegroundColor $dispatcherLaneColor
$dispatcherMode = if ([bool]$state.dispatcherArmed) { "ARMED" } else { "DISARMED" }
$dispatcherModeColor = if ([bool]$state.dispatcherArmed) { "Yellow" } else { "DarkGray" }
Write-Host "Dispatcher mode: $dispatcherMode (last managed start)" -ForegroundColor $dispatcherModeColor

$dispatcherPortPid = Get-PortOwnerPid -Port $DispatcherPort
$dispatcherHealthy = $false
if ($dispatcherPortPid) {
    $dispatcherHealth = try {
        (Invoke-RestMethod -Uri "http://localhost:$DispatcherPort/actuator/health" -TimeoutSec 3).status
    } catch { "no response" }
    $dispatcherManaged = Test-ManagedProcess -ProcessId $dispatcherPortPid -Kind "Dispatcher"
    $dispatcherHealthy = $dispatcherHealth -eq "UP" -and $dispatcherManaged
    $dispatcherColor = if ($dispatcherHealthy) { "Green" } else { "Yellow" }
    Write-Host "AI Dispatcher: running (PID $dispatcherPortPid, health=$dispatcherHealth, managed=$dispatcherManaged)" `
        -ForegroundColor $dispatcherColor
} else {
    Write-Host "AI Dispatcher: not running (main application is unaffected)" -ForegroundColor DarkGray
}
if ($RequireDispatcher -and (-not $dispatcherDbHealthy -or -not $dispatcherHealthy)) {
    $allHealthy = $false
}

# ngrok requirement is controlled independently.
$ngrokPortPid = Get-PortOwnerPid -Port $NgrokApiPort
if ($ngrokPortPid) {
    $url = Get-NgrokPublicUrl -TimeoutSec 5
    Write-Host "ngrok:          running (PID $ngrokPortPid)" -ForegroundColor Green
    if ($url) { Write-Host "  webhook:      $url/api/line/webhook" }
} else {
    Write-Host "ngrok:          not running" -ForegroundColor DarkGray
    if (-not $NoNgrokRequired) { $allHealthy = $false }
}

if (-not $SkipLineWebhookTest -and -not $NoNgrokRequired) {
    Write-Host "LINE webhook:   running official end-to-end test..." -ForegroundColor Yellow
    $lineTest = Test-LineWebhookEndToEnd
    if ($lineTest.Success) {
        Write-Host "LINE webhook:   connected (LINE -> ngrok -> Spring Boot)" -ForegroundColor Green
    } else {
        Write-Host "LINE webhook:   disconnected" -ForegroundColor Red
        if ($lineTest.Reason) { Write-Host "  reason:       $($lineTest.Reason)" -ForegroundColor Yellow }
        if ($lineTest.Detail) { Write-Host "  detail:       $($lineTest.Detail)" -ForegroundColor Yellow }
        if ($lineTest.Error) { Write-Host "  error:        $($lineTest.Error)" -ForegroundColor Yellow }
        $allHealthy = $false
    }
} elseif ($SkipLineWebhookTest) {
    Write-Host "LINE webhook:   official test skipped (-SkipLineWebhookTest)" -ForegroundColor DarkGray
}

if ($state.startedAt) {
    Write-Host ""
    Write-Host "Last dev-start.ps1 time: $($state.startedAt)" -ForegroundColor DarkGray
}
if (-not $RequireDispatcher) {
    Write-Host "Dispatcher is failure-isolated. Use -RequireDispatcher for strict checking." -ForegroundColor DarkGray
}

if (-not $allHealthy) { exit 1 }
exit 0

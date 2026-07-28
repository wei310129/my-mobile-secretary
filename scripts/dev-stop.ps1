<#
.SYNOPSIS
  Stops the main application, AI Dispatcher, and ngrok. Databases stay up by default.

.PARAMETER Docker
  Also stops both Compose projects while retaining their volumes.

.PARAMETER RemoveVolumes
  Requires -Docker. Permanently removes both local database volumes.

.PARAMETER VerboseOutput
  Prints normal per-component stop progress in addition to the final summary.
#>
param(
    [switch]$Docker,
    [switch]$RemoveVolumes,
    [switch]$VerboseOutput
)

. "$PSScriptRoot\_devops-common.ps1"
$script:DevVerboseOutput = [bool]$VerboseOutput
Set-Location $RepoRoot

if ($RemoveVolumes -and -not $Docker) {
    Write-Host "-RemoveVolumes requires -Docker. Nothing was stopped." -ForegroundColor Red
    exit 1
}

$lifecycleLease = Enter-DevLifecycleCoordination -Action stop
$lifecycleOutcome = 'FAILED'
try {
Write-DevProgress -Message "=== Stopping development environment ===" -ForegroundColor Cyan
Invoke-CoordinatorDispatcherDrainPreflight
$state = Read-DevState

$dispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $state.dispatcherPid `
    -Port $DispatcherPort -Kind "Dispatcher"
$laneSnapshot = Get-DispatcherLaneSnapshot
if ($dispatcherPid -and -not $laneSnapshot) {
    Write-Host "Dispatcher is running but its durable lane cannot be inspected; stop refused." `
        -ForegroundColor Red
    Write-Host "Restore Dispatcher DB visibility before stopping the environment." -ForegroundColor Yellow
    exit 2
}
if ($laneSnapshot -and $laneSnapshot.ActiveRunId) {
    Write-Host "Dispatcher lane is $($laneSnapshot.State) with active run $($laneSnapshot.ActiveRunId); stop refused." `
        -ForegroundColor Red
    Write-Host "Wait for the run to finish before stopping the environment." -ForegroundColor Yellow
    exit 2
}

# Stop Dispatcher first so it cannot poll while the main application is shutting down.
if ($dispatcherPid) {
    $stopResult = Stop-ProcessTree -ProcessId $dispatcherPid -Label "AI Dispatcher" -Port $DispatcherPort
    if (-not $stopResult.Success) { throw "Dispatcher stop verification failed; ownership was retained." }
} else {
    $dispatcherPortOwner = Get-PortOwnerPid -Port $DispatcherPort
    if ($dispatcherPortOwner) {
        Write-Host "  Dispatcher port $DispatcherPort belongs to unmanaged PID $dispatcherPortOwner; it was not killed." -ForegroundColor Yellow
    } else {
        Write-DevProgress -Message "  AI Dispatcher is not running." -ForegroundColor DarkGray
    }
}

$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    $stopResult = Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot" -Port $AppPort
    if (-not $stopResult.Success) { throw "Spring Boot stop verification failed; ownership was retained." }
} else {
    $appPortOwner = Get-PortOwnerPid -Port $AppPort
    if ($appPortOwner) {
        Write-Host "  Main port $AppPort belongs to unmanaged PID $appPortOwner; it was not killed." -ForegroundColor Yellow
    } else {
        Write-DevProgress -Message "  Spring Boot is not running." -ForegroundColor DarkGray
    }
}

$ngrokPid = Resolve-ManagedProcessId -TrackedProcessId $state.ngrokPid `
    -Port $NgrokApiPort -Kind "Ngrok"
if ($ngrokPid) {
    $stopResult = Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok" -Port $NgrokApiPort
    if (-not $stopResult.Success) { throw "ngrok stop verification failed; ownership was retained." }
} else {
    Write-DevProgress -Message "  ngrok is not running." -ForegroundColor DarkGray
}

Write-DevState -Updates @{
    springBootPid = $null
    dispatcherPid = $null
    ngrokPid      = $null
    ngrokUrl      = $null
    dispatcherArmed = $false
}

if ($Docker) {
    Assert-CommandAvailable -Name "docker"
    $dockerStopFailed = $false
    if ($RemoveVolumes) {
        Write-DevProgress -Message "  Stopping main Compose project and removing its volumes..." -ForegroundColor Red
        $mainComposeOutput = docker compose down -v 2>&1
        if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true; $mainComposeOutput | ForEach-Object { Write-Host $_ } }

        if (Test-Path $DispatcherComposeFile) {
            Write-DevProgress -Message "  Stopping Dispatcher Compose project and removing its isolated volume..." -ForegroundColor Red
            $dispatcherComposeOutput = docker compose -f $DispatcherComposeFile down -v 2>&1
            if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true; $dispatcherComposeOutput | ForEach-Object { Write-Host $_ } }
        }
    } else {
        Write-DevProgress -Message "  Stopping main Compose project (volumes retained)..." -ForegroundColor Yellow
        $mainComposeOutput = docker compose stop 2>&1
        if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true; $mainComposeOutput | ForEach-Object { Write-Host $_ } }

        if (Test-Path $DispatcherComposeFile) {
            Write-DevProgress -Message "  Stopping Dispatcher Compose project (isolated volume retained)..." -ForegroundColor Yellow
            $dispatcherComposeOutput = docker compose -f $DispatcherComposeFile stop 2>&1
            if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true; $dispatcherComposeOutput | ForEach-Object { Write-Host $_ } }
        }
    }
    if ($dockerStopFailed) {
        Write-Host "At least one Compose project failed to stop." -ForegroundColor Red
        exit 1
    }
} else {
    Write-DevProgress -Message "  Both database environments remain running. Use -Docker to stop them." -ForegroundColor DarkGray
}

$databaseSummary = if ($Docker -and $RemoveVolumes) { "databases=removed" } elseif ($Docker) { "databases=stopped-volumes-retained" } else { "databases=running" }
Write-Host "Development environment stopped: applications=stopped; ngrok=stopped; $databaseSummary." -ForegroundColor Green
$lifecycleOutcome = 'READY'
} finally {
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action stop -Outcome $lifecycleOutcome
}

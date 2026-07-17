<#
.SYNOPSIS
  Stops the main application, AI Dispatcher, and ngrok. Databases stay up by default.

.PARAMETER Docker
  Also stops both Compose projects while retaining their volumes.

.PARAMETER RemoveVolumes
  Requires -Docker. Permanently removes both local database volumes.
#>
param(
    [switch]$Docker,
    [switch]$RemoveVolumes
)

. "$PSScriptRoot\_devops-common.ps1"
Set-Location $RepoRoot

if ($RemoveVolumes -and -not $Docker) {
    Write-Host "-RemoveVolumes requires -Docker. Nothing was stopped." -ForegroundColor Red
    exit 1
}

Write-Host "=== Stopping development environment ===" -ForegroundColor Cyan
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
    Stop-ProcessTree -ProcessId $dispatcherPid -Label "AI Dispatcher"
} else {
    $dispatcherPortOwner = Get-PortOwnerPid -Port $DispatcherPort
    if ($dispatcherPortOwner) {
        Write-Host "  Dispatcher port $DispatcherPort belongs to unmanaged PID $dispatcherPortOwner; it was not killed." -ForegroundColor Yellow
    } else {
        Write-Host "  AI Dispatcher is not running." -ForegroundColor DarkGray
    }
}

$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot"
} else {
    $appPortOwner = Get-PortOwnerPid -Port $AppPort
    if ($appPortOwner) {
        Write-Host "  Main port $AppPort belongs to unmanaged PID $appPortOwner; it was not killed." -ForegroundColor Yellow
    } else {
        Write-Host "  Spring Boot is not running." -ForegroundColor DarkGray
    }
}

$ngrokPid = Resolve-ManagedProcessId -TrackedProcessId $state.ngrokPid `
    -Port $NgrokApiPort -Kind "Ngrok"
if ($ngrokPid) {
    Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok"
} else {
    Write-Host "  ngrok is not running." -ForegroundColor DarkGray
}

Write-DevState -Updates @{
    springBootPid = $null
    dispatcherPid = $null
    ngrokPid      = $null
    ngrokUrl      = $null
}

if ($Docker) {
    Assert-CommandAvailable -Name "docker"
    $dockerStopFailed = $false
    if ($RemoveVolumes) {
        Write-Host "  Stopping main Compose project and removing its volumes..." -ForegroundColor Red
        docker compose down -v
        if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true }

        if (Test-Path $DispatcherComposeFile) {
            Write-Host "  Stopping Dispatcher Compose project and removing its isolated volume..." -ForegroundColor Red
            docker compose -f $DispatcherComposeFile down -v
            if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true }
        }
    } else {
        Write-Host "  Stopping main Compose project (volumes retained)..." -ForegroundColor Yellow
        docker compose stop
        if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true }

        if (Test-Path $DispatcherComposeFile) {
            Write-Host "  Stopping Dispatcher Compose project (isolated volume retained)..." -ForegroundColor Yellow
            docker compose -f $DispatcherComposeFile stop
            if ($LASTEXITCODE -ne 0) { $dockerStopFailed = $true }
        }
    }
    if ($dockerStopFailed) {
        Write-Host "At least one Compose project failed to stop." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  Both database environments remain running. Use -Docker to stop them." -ForegroundColor DarkGray
}

Write-Host "=== Stopped ===" -ForegroundColor Cyan

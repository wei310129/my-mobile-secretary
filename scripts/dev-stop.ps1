
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

# Stop Dispatcher first so it cannot poll while the main application is shutting down.
$dispatcherResult = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component Dispatcher -StopAdapter {
    param($proof)
    $laneSnapshot = Get-DispatcherLaneSnapshot
    if (-not $laneSnapshot) {
        throw 'Dispatcher is running but its durable lane cannot be inspected; stop refused.'
    }
    if ($laneSnapshot.ActiveRunId) {
        throw "Dispatcher lane is $($laneSnapshot.State) with active run $($laneSnapshot.ActiveRunId); stop refused."
    }
    Stop-ProcessTree -ProcessId $proof.ProcessId -Label $proof.Definition.Label -Port $proof.Definition.Port
}
if ($dispatcherResult.Outcome -ne 'READY') { throw $dispatcherResult.Reason }
if ($dispatcherResult.Disposition -eq 'ALREADY_STOPPED') {
    $owner = Get-PortOwnerPid -Port $DispatcherPort
    if ($owner) { throw "Dispatcher state is empty but port $DispatcherPort is owned by unmanaged PID $owner; it was not stopped." }
}

$springResult = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component SpringBoot -StopAdapter {
    param($proof)
    Stop-ProcessTree -ProcessId $proof.ProcessId -Label $proof.Definition.Label -Port $proof.Definition.Port
}
if ($springResult.Outcome -ne 'READY') { throw $springResult.Reason }
if ($springResult.Disposition -eq 'ALREADY_STOPPED') {
    $owner = Get-PortOwnerPid -Port $AppPort
    if ($owner) { throw "Spring state is empty but port $AppPort is owned by unmanaged PID $owner; it was not stopped." }
}

$ngrokResult = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component Ngrok -StopAdapter {
    param($proof)
    Stop-ProcessTree -ProcessId $proof.ProcessId -Label $proof.Definition.Label -Port $proof.Definition.Port
}
if ($ngrokResult.Outcome -ne 'READY') { throw $ngrokResult.Reason }
if ($ngrokResult.Disposition -eq 'ALREADY_STOPPED') {
    $owner = Get-PortOwnerPid -Port $NgrokApiPort
    if ($owner) { throw "ngrok state is empty but port $NgrokApiPort is owned by unmanaged PID $owner; it was not stopped." }
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


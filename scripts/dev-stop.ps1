
<#
.SYNOPSIS
  Stops the main application, AI Dispatcher, and ngrok. Databases stay up by default.

.PARAMETER Docker
  Validates shared-persistent containers and reports them retained; it never stops or removes them.

.PARAMETER RemoveVolumes
  Rejected because shared-persistent volumes are protected.

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
if ($RemoveVolumes) {
    Write-Host "Shared-persistent containers and volumes are protected; -RemoveVolumes is not permitted." -ForegroundColor Red
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
    $sharedStatus = Get-SharedInfrastructureStatus -Name @('main-postgres','main-redis','dispatcher-postgres')
    if ($sharedStatus.Outcome -eq 'BLOCKED') {
        Write-Host "Shared infrastructure was not changed: $($sharedStatus.Classification) ($($sharedStatus.Reason))." -ForegroundColor Red
        exit 1
    }
    Write-DevProgress -Message "  Shared-persistent Docker containers and volumes remain running by policy." -ForegroundColor DarkGray
} else {
    Write-DevProgress -Message "  Both shared-persistent database environments remain running by policy." -ForegroundColor DarkGray
}

$databaseSummary = if ($Docker) { "databases=shared-persistent-retained" } else { "databases=running" }
Write-Host "Development environment stopped: applications=stopped; ngrok=stopped; $databaseSummary." -ForegroundColor Green
$lifecycleOutcome = 'READY'
} finally {
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action stop -Outcome $lifecycleOutcome
}


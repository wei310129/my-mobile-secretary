<#
.SYNOPSIS
  Restarts the managed runtime generation. Databases stay up; ngrok is generation-bound and restarts with the application.

.PARAMETER Full
  Restarts application processes and revalidates shared-persistent Docker infrastructure without stopping it.

.PARAMETER Profile
  Spring profile for the main application. Defaults to local.

.PARAMETER SkipDispatcher
  Restarts only the main application and does not touch the AI Dispatcher.

.PARAMETER NoNgrok
  Preserves a local-only restart without starting ngrok or running the LINE webhook probe.

.PARAMETER ArmDispatcher
  Restarts both applications with explicitly armed Dispatcher automation.
#>
param(
    [switch]$Full,
    [string]$Profile = "local",
    [switch]$NoNgrok,
    [switch]$SkipDispatcher,
    [switch]$ArmDispatcher,
    [switch]$AllowDirtyWorktree
)

. "$PSScriptRoot\_devops-common.ps1"
$script:DevVerboseOutput = $false
Set-Location $RepoRoot

$startParameters = @{ Profile = $Profile }
if ($NoNgrok) { $startParameters["NoNgrok"] = $true }
if ($SkipDispatcher) { $startParameters["SkipDispatcher"] = $true }
if ($ArmDispatcher) {
    if ($SkipDispatcher) { throw "-SkipDispatcher cannot be combined with -ArmDispatcher." }
    Enable-DispatcherAutomationEnvironment -AllowDirtyWorktree:$AllowDirtyWorktree
    Assert-DispatcherSessionReady
    $startParameters["ArmDispatcher"] = $true
    if ($AllowDirtyWorktree) { $startParameters["AllowDirtyWorktree"] = $true }
} elseif ($AllowDirtyWorktree) {
    throw "-AllowDirtyWorktree requires -ArmDispatcher."
}

$lifecycleLease = Enter-DevLifecycleCoordination -Action restart
$lifecycleOutcome = 'FAILED'
try {
if ($Full) {
    Write-Host "=== Full restart ===" -ForegroundColor Cyan
    Write-Host "Shared persistent Docker infrastructure is preserved and revalidated by dev-start." -ForegroundColor DarkGray
    & "$PSScriptRoot\dev-stop.ps1"
    $stopExitCode = Get-SafeChildExitCode -Succeeded $?
    if ($stopExitCode -ne 0) { exit $stopExitCode }
    & "$PSScriptRoot\dev-start.ps1" @startParameters
    $childExitCode = Get-SafeChildExitCode -Succeeded $?
    if ($childExitCode -eq 0) { $lifecycleOutcome = 'READY' }
    exit $childExitCode
}

Write-Host "=== Restarting main application and AI Dispatcher ===" -ForegroundColor Cyan
if (-not $SkipDispatcher) { Invoke-CoordinatorDispatcherDrainPreflight }
$state = Read-DevState

if (-not $SkipDispatcher) {
    $dispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $state.dispatcherPid `
        -Port $DispatcherPort -Kind "Dispatcher"
    $laneSnapshot = Get-DispatcherLaneSnapshot
    if ($dispatcherPid -and -not $laneSnapshot) {
        Write-Host "Dispatcher is running but its durable lane cannot be inspected; restart refused." `
            -ForegroundColor Red
        Write-Host "Use -SkipDispatcher to restart only the main application." -ForegroundColor Yellow
        exit 2
    }
    if ($laneSnapshot -and $laneSnapshot.ActiveRunId) {
        Write-Host "Dispatcher lane is $($laneSnapshot.State) with active run $($laneSnapshot.ActiveRunId); restart refused." `
            -ForegroundColor Red
        Write-Host "Wait for the run to finish. Use -SkipDispatcher only when restarting the main app is unavoidable." `
            -ForegroundColor Yellow
        exit 2
    }
    if ($dispatcherPid) {
        $dispatcherStop = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component Dispatcher -StopAdapter {
            param($proof)
            Stop-ManagedExactProcessTree -RootProcessId $proof.ProcessId -ExpectedPort $proof.Definition.Port `
                -PortObservationAdapter { param($port) Get-DevPortObservation -Port $port }
        }
        if ($dispatcherStop.Outcome -ne 'READY') { throw "Dispatcher stop verification failed; restart aborted: $($dispatcherStop.ReasonCode)" }
    } else {
        $dispatcherPortOwner = Get-PortOwnerPid -Port $DispatcherPort
        if ($dispatcherPortOwner) {
            Write-Host "  Dispatcher port is owned by unmanaged PID $dispatcherPortOwner; main restart will continue." -ForegroundColor Yellow
        } else {
            Write-Host "  AI Dispatcher was not running." -ForegroundColor DarkGray
        }
    }
}

$ngrokStop = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component Ngrok -StopAdapter {
    param($proof)
    Stop-ManagedExactProcessTree -RootProcessId $proof.ProcessId -ExpectedPort $proof.Definition.Port `
        -PortObservationAdapter { param($port) Get-DevPortObservation -Port $port }
}
if ($ngrokStop.Outcome -ne 'READY') { throw "ngrok stop verification failed; restart aborted: $($ngrokStop.ReasonCode)" }

$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    $springStop = Invoke-ManagedComponentStop -Worktree $RepoRoot -Component SpringBoot -StopAdapter {
        param($proof)
        Stop-ManagedExactProcessTree -RootProcessId $proof.ProcessId -ExpectedPort $proof.Definition.Port `
            -PortObservationAdapter { param($port) Get-DevPortObservation -Port $port }
    }
    if ($springStop.Outcome -ne 'READY') { throw "Spring Boot stop verification failed; restart aborted: $($springStop.ReasonCode)" }
} else {
    $appPortOwner = Get-PortOwnerPid -Port $AppPort
    if ($appPortOwner) {
        Write-Host "Main port $AppPort belongs to unmanaged PID $appPortOwner. Restart aborted to avoid killing it." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Spring Boot was not running." -ForegroundColor DarkGray
}

& "$PSScriptRoot\dev-start.ps1" @startParameters
$childExitCode = Get-SafeChildExitCode -Succeeded $?
if ($childExitCode -eq 0) { $lifecycleOutcome = 'READY' }
exit $childExitCode
} finally {
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action restart -Outcome $lifecycleOutcome
}

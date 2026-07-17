<#
.SYNOPSIS
  Restarts the main application and AI Dispatcher. Databases and ngrok stay up by default.

.PARAMETER Full
  Stops both Compose projects and then starts the full environment. Volumes are retained.

.PARAMETER Profile
  Spring profile for the main application. Defaults to local.

.PARAMETER SkipDispatcher
  Restarts only the main application and does not touch the AI Dispatcher.
#>
param(
    [switch]$Full,
    [string]$Profile = "local",
    [switch]$SkipDispatcher
)

. "$PSScriptRoot\_devops-common.ps1"
Set-Location $RepoRoot

$startParameters = @{ Profile = $Profile }
if ($SkipDispatcher) { $startParameters["SkipDispatcher"] = $true }

if ($Full) {
    Write-Host "=== Full restart ===" -ForegroundColor Cyan
    & "$PSScriptRoot\dev-stop.ps1" -Docker
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$PSScriptRoot\dev-start.ps1" @startParameters
    exit $LASTEXITCODE
}

Write-Host "=== Restarting main application and AI Dispatcher ===" -ForegroundColor Cyan
$state = Read-DevState

if (-not $SkipDispatcher) {
    $dispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $state.dispatcherPid `
        -Port $DispatcherPort -Kind "Dispatcher"
    if ($dispatcherPid) {
        Stop-ProcessTree -ProcessId $dispatcherPid -Label "AI Dispatcher (old)"
    } else {
        $dispatcherPortOwner = Get-PortOwnerPid -Port $DispatcherPort
        if ($dispatcherPortOwner) {
            Write-Host "  Dispatcher port is owned by unmanaged PID $dispatcherPortOwner; main restart will continue." -ForegroundColor Yellow
        } else {
            Write-Host "  AI Dispatcher was not running." -ForegroundColor DarkGray
        }
    }
}

$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot (old)"
} else {
    $appPortOwner = Get-PortOwnerPid -Port $AppPort
    if ($appPortOwner) {
        Write-Host "Main port $AppPort belongs to unmanaged PID $appPortOwner. Restart aborted to avoid killing it." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Spring Boot was not running." -ForegroundColor DarkGray
}

& "$PSScriptRoot\dev-start.ps1" @startParameters
exit $LASTEXITCODE

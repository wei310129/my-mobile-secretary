<#
.SYNOPSIS
  Explicitly arms the isolated AI Dispatcher after fail-closed preflight checks.

.DESCRIPTION
  Secrets and actor/workspace IDs are read only from process environment variables. This script
  never accepts them as command-line arguments and never writes them to the development state file.
#>
param(
    [string]$Profile = "local",
    [switch]$Full,
    [switch]$AllowDirtyWorktree
)

. "$PSScriptRoot\_devops-common.ps1"
Normalize-ProcessPathEnvironment

try {
    Enable-DispatcherAutomationEnvironment -AllowDirtyWorktree:$AllowDirtyWorktree
    Assert-DispatcherSessionReady
} catch {
    Write-Host "Dispatcher arm preflight failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

Write-Host "Dispatcher preflight passed. Restarting both independent applications in ARMED mode." `
    -ForegroundColor Yellow
& "$PSScriptRoot\dev-restart.ps1" -Profile $Profile -Full:$Full -ArmDispatcher `
    -AllowDirtyWorktree:$AllowDirtyWorktree
exit $LASTEXITCODE

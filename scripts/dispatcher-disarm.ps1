<#
.SYNOPSIS
  Restarts the environment with all Dispatcher automation flags disabled.
#>
param(
    [string]$Profile = "local",
    [switch]$Full
)

Write-Host "Disarming Dispatcher automation through a guarded restart." -ForegroundColor Yellow
& "$PSScriptRoot\dev-restart.ps1" -Profile $Profile -Full:$Full
exit $LASTEXITCODE

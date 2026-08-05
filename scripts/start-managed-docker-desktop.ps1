[CmdletBinding()]
param(
    [ValidateRange(1, 900)][int]$TimeoutSeconds = 180,
    [AllowNull()][string]$StateRoot
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\coordination-common.ps1"
. "$PSScriptRoot\managed-docker-desktop.ps1"

try {
    $result = Invoke-ManagedDockerDesktop -RepoRoot (Split-Path -Parent $PSScriptRoot) `
        -TimeoutSeconds $TimeoutSeconds -StateRoot $StateRoot
    $result | ConvertTo-Json -Depth 12 -Compress
    if ($result.outcome -eq 'READY' -and $result.daemonReady) { exit 0 }
    exit 40
} catch {
    Write-Host ("Managed Docker Desktop failed closed: {0}" -f $_.Exception.Message)
    exit 40
}

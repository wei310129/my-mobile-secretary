[CmdletBinding()]
param(
    [ValidateRange(1, 300)][int]$StabilityWindowSeconds = 5,
    [ValidateRange(0, 60)][int]$StabilityIntervalSeconds = 1,
    [ValidateRange(1, 600)][int]$StartupTimeoutSeconds = 180,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\coordination-common.ps1"
. "$PSScriptRoot\docker-shared-infrastructure.ps1"

try {
    $result = Ensure-ManagedDockerDaemon -StabilityWindowSeconds $StabilityWindowSeconds -StabilityIntervalSeconds $StabilityIntervalSeconds -StartupTimeoutSeconds $StartupTimeoutSeconds
    if ($Json) {
        $result | ConvertTo-Json -Depth 12
    } else {
        Write-Host ("Managed Docker Desktop: outcome={0}; classification={1}; started={2}" -f $result.Outcome, $result.Classification, $result.Started)
        if ($result.Reason) { Write-Host ("  reason: {0}" -f $result.Reason) }
    }
    if ($result.Outcome -eq 'READY') { exit 0 }
    if ($result.Classification -eq 'AGENT_ASYNC_TOOL_REQUIRED') { exit 10 }
    exit 40
} catch {
    if ($Json) {
        [pscustomobject]@{ outcome='FAILED'; classification='DAEMON_NOT_READY'; reason=$_.Exception.Message } | ConvertTo-Json -Compress
    } else {
        Write-Host ("Managed Docker Desktop failed: {0}" -f $_.Exception.Message)
    }
    exit 50
}

[CmdletBinding()]
param(
    [string]$StateRoot,
    [string]$DevStateFile,
    [string]$SessionId = ([guid]::NewGuid().ToString()),
    [string]$AgentId = 'local-operator'
)

. "$PSScriptRoot\_devops-common.ps1"
if ([string]::IsNullOrWhiteSpace($StateRoot)) { $StateRoot = Get-CoordinationDefaultRoot }
if ($DevStateFile) { $StateFile = $DevStateFile }
$state = Read-DevState
$doctor = Get-CoordinationDoctorSnapshot -StateRoot $StateRoot
$receipt = [ordered]@{
    operationId = "handoff-$SessionId"
    sessionId = $SessionId
    agentId = $AgentId
    outcome = $doctor.outcome
    disposition = 'kept-persistent'
    devState = $state
    doctor = $doctor
    cleanup = 'none; handoff is read-only'
}
Write-CoordinationReceipt -StateRoot $StateRoot -Receipt $receipt
$receipt | ConvertTo-Json -Depth 12

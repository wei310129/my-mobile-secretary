[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Worktree,
    [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\project-tool-policy.ps1"
. "$PSScriptRoot\coordination-common.ps1"
. "$PSScriptRoot\managed-process-lifecycle.ps1"

try {
    $target = Resolve-ProjectManagedWorktree -ProjectRoot $projectRoot -Worktree $Worktree
    # Stop-ProcessTree remains the official project implementation. Loading it from the
    # target worktree preserves its ports and lifecycle conventions; this wrapper supplies
    # the ownership proof that the generic function intentionally does not infer.
    . (Join-Path $target 'scripts\_devops-common.ps1')
    if ($ValidateOnly) {
        $stateFile = Join-Path $target 'scripts\.dev-state.json'
        $state = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $proof = Get-ManagedProcessOwnershipProof -Worktree $target -Component $Component -State $state
        $disposition = if ($proof.PSObject.Properties['Disposition']) { $proof.Disposition } else { $null }
        $reason = if ($proof.PSObject.Properties['Reason']) { $proof.Reason } else { $null }
        [pscustomobject]@{
            outcome=$proof.Outcome;component=$Component;disposition=$disposition
            processId=$proof.ProcessId;resource=$proof.Definition.Resource;reason=$reason
        } | ConvertTo-Json -Compress
        if ($proof.Outcome -eq 'READY') { exit 0 }
        exit 40
    }
    $result = Invoke-ManagedComponentStop -Worktree $target -Component $Component -StopAdapter {
        param($proof)
        if ($proof.Definition.Component -eq 'Dispatcher') {
            $lane = Get-DispatcherLaneSnapshot
            if (-not $lane) { throw 'Dispatcher durable lane cannot be inspected; stop refused.' }
            if ($lane.ActiveRunId) { throw 'Dispatcher has an active run; stop refused.' }
        }
        Stop-ProcessTree -ProcessId $proof.ProcessId -Label $proof.Definition.Label -Port $proof.Definition.Port
    }
    if ($result.Outcome -ne 'READY') {
        Write-Host ("Managed stop refused: {0}" -f $result.Reason)
        exit 40
    }
    $receiptId = if ($result.PSObject.Properties['ReceiptId']) { $result.ReceiptId } else { $null }
    [pscustomobject]@{
        outcome=$result.Outcome;component=$Component;disposition=$result.Disposition
        changed=$result.Changed;receiptId=$receiptId
    } | ConvertTo-Json -Compress
    exit 0
} catch {
    Write-Host ("Managed stop failed closed: {0}" -f $_.Exception.Message)
    exit 40
}


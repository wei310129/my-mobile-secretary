[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scriptsRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')
$repoRoot=Split-Path -Parent $scriptsRoot
$stateRoot=Join-Path ([IO.Path]::GetTempPath()) ("mms-managed-operation-"+[guid]::NewGuid().ToString('n').Substring(0,8))
$assertions=0
$previousSandbox=$env:CODEX_SANDBOX
function Assert-ManagedOperation([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:assertions++}
function New-ReadyProbe {[pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;Version='fake'}}
function Copy-Json($Value){$Value|ConvertTo-Json -Depth 20|ConvertFrom-Json}

try {
    Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue
    $ready=New-ReadyProbe
    $overrides=@{PowerShell=$ready;Git=$ready;Java=$ready;Docker=$ready;Runtime=$ready}
    $hostRuntime=New-EnvironmentSnapshot -Capability DEV_RUNTIME -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $hostLine=New-EnvironmentSnapshot -Capability LINE_E2E -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $runtimeReceipt=New-EnvironmentManagedOperationReceipt -Capability DEV_RUNTIME -Operation RUNTIME_START `
        -Generation generation-a -Snapshot $hostRuntime -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    $lineReceipt=New-EnvironmentManagedOperationReceipt -Capability LINE_E2E -Operation LINE_CONNECTIVITY_PROBE `
        -Generation generation-a -Snapshot $hostLine -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ((Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop).Ready) `
        'valid host managed runtime receipt was rejected'
    Assert-ManagedOperation ((Test-EnvironmentManagedOperationReceipt -ReceiptPath $lineReceipt.Path -Capability LINE_E2E `
        -Operation LINE_CONNECTIVITY_PROBE -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop).Ready) `
        'valid host managed LINE receipt was rejected'
    $primaryRoot=(Get-EnvironmentGitLayout -RepoRoot $repoRoot).PrimaryRoot
    $crossWorktree=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $primaryRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $crossWorktree.Ready) 'linked-worktree receipt was accepted for the primary worktree'

    $env:CODEX_SANDBOX='1'
    try {
        $probeIssue=Write-EnvironmentIssue -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DEV_RUNTIME `
            -Expected ready -Actual denied -RecheckKind MANAGED_OPERATION -Participation PROBE_ONLY `
            -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    } finally {
        if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    }
    $resolved=Resolve-EnvironmentIssueByManagedOperation -Issue $probeIssue -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($resolved.status -eq 'FIXED') 'probe-only issue was not closed by matching managed operation'
    Assert-ManagedOperation ($resolved.resolutionEvidence.resolutionKind -eq 'MANAGED_OPERATION_SUPERSESSION') `
        'managed operation resolution was not typed'
    Assert-ManagedOperation ($resolved.resolutionEvidence.issueCallerKind -eq 'sandbox' -and `
        $resolved.resolutionEvidence.operationCallerKind -eq 'host') 'probe and operation callers were not independently audited'
    $context=Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (Test-EnvironmentIssueResolutionEvidence -Issue $resolved -Context $context -RepoRoot $repoRoot) `
        'durable managed-operation resolution evidence was rejected'
    $reviewText=Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-environment-review.ps1') -Raw -Encoding UTF8
    Assert-ManagedOperation ($reviewText.Contains('Get-EnvironmentActiveServiceGeneration')) 'Automatic review does not fence managed evidence to the active generation'
    Assert-ManagedOperation ($reviewText.Contains('Resolve-EnvironmentIssueByManagedOperation')) 'Automatic review does not use typed managed-operation resolution'

    $env:CODEX_SANDBOX='1'
    try {
        $participantIssue=Write-EnvironmentIssue -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability LINE_E2E `
            -Expected ready -Actual denied -RecheckKind MANAGED_OPERATION -Participation OPERATION_PARTICIPANT `
            -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    } finally {
        if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    }
    $participantRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $participantIssue -ReceiptPath $lineReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$participantRejected=$true}
    Assert-ManagedOperation $participantRejected 'denied caller that participated in the operation was silently superseded'

    foreach($case in @(
        @{Name='generation';Capability='DEV_RUNTIME';Operation='RUNTIME_START';Generation='generation-b'},
        @{Name='capability';Capability='SOURCE_WRITE';Operation='SOURCE_WRITE';Generation='generation-a'},
        @{Name='operation';Capability='DEV_RUNTIME';Operation='LINE_CONNECTIVITY_PROBE';Generation='generation-a'}
    )) {
        $result=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability $case.Capability `
            -Operation $case.Operation -Generation $case.Generation -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
        Assert-ManagedOperation (-not $result.Ready) "wrong $($case.Name) fence was accepted"
    }

    $document=Read-EnvironmentJson -Path $runtimeReceipt.Path
    $tampered=Copy-Json $document;$tampered.contractFingerprint='wrong-contract'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $tampered
    $badContract=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badContract.Ready) 'wrong contract receipt was accepted'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $document

    $wrongIssuer=Copy-Json $document;$wrongIssuer.issuer='UNTRUSTED_HOST_JSON';$wrongIssuer.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $wrongIssuer
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $wrongIssuer
    $badIssuer=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badIssuer.Ready) 'arbitrary host JSON with a wrong issuer was accepted'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $document

    $expired=Copy-Json $document;$expired.issuedAt=[datetime]::UtcNow.AddMinutes(-20).ToString('o');$expired.expiresAt=[datetime]::UtcNow.AddMinutes(-5).ToString('o')
    $expired.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $expired
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $expired
    $badExpiry=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badExpiry.Ready) 'expired managed operation receipt was accepted'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $document

    $arbitrary=Copy-Json $document;$arbitrary.operationCallerKind='sandbox';$arbitrary.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $arbitrary
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $arbitrary
    $badCaller=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badCaller.Ready) 'sandbox caller forged a host managed operation receipt'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $document

    $wrongWorktree=Copy-Json $document;$wrongWorktree.worktreeId='wrong-worktree';$wrongWorktree.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $wrongWorktree
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $wrongWorktree
    $badWorktree=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badWorktree.Ready) 'wrong worktree receipt was accepted'

    [pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress
} finally {
    if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    if(Test-Path -LiteralPath $stateRoot){Remove-Item -LiteralPath $stateRoot -Recurse -Force}
}

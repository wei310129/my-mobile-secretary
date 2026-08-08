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
    $hostDocker=New-EnvironmentSnapshot -Capability DOCKER_TEST -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $runtimeReceipt=New-EnvironmentManagedOperationReceipt -Capability DEV_RUNTIME -Operation RUNTIME_START `
        -Generation generation-a -Snapshot $hostRuntime -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    $lineReceipt=New-EnvironmentManagedOperationReceipt -Capability LINE_E2E -Operation LINE_CONNECTIVITY_PROBE `
        -Generation generation-a -Snapshot $hostLine -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    $dockerReceipt=New-EnvironmentManagedOperationReceipt -Capability DOCKER_TEST -Operation DOCKER_SHARED_INFRASTRUCTURE_READY `
        -Generation generation-a -Snapshot $hostDocker -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ((Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop).Ready) `
        'valid host managed runtime receipt was rejected'
    Assert-ManagedOperation ((Test-EnvironmentManagedOperationReceipt -ReceiptPath $lineReceipt.Path -Capability LINE_E2E `
        -Operation LINE_CONNECTIVITY_PROBE -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop).Ready) `
        'valid host managed LINE receipt was rejected'
    Assert-ManagedOperation ((Test-EnvironmentManagedOperationReceipt -ReceiptPath $dockerReceipt.Path -Capability DOCKER_TEST `
        -Operation DOCKER_SHARED_INFRASTRUCTURE_READY -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop).Ready) `
        'valid host managed Docker/shared-infrastructure receipt was rejected'
    $contractWithDocker=(Get-EnvironmentContract -RepoRoot $repoRoot).Fingerprint
    $dockerOperation=$script:ManagedOperationCapabilities['DOCKER_TEST']
    $script:ManagedOperationCapabilities.Remove('DOCKER_TEST')
    try {$contractWithoutDocker=(Get-EnvironmentContract -RepoRoot $repoRoot).Fingerprint}
    finally {$script:ManagedOperationCapabilities['DOCKER_TEST']=$dockerOperation}
    Assert-ManagedOperation ($contractWithDocker -ne $contractWithoutDocker) 'Docker managed operation was not bound into the contract fingerprint'
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
    $context=Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    $historicalDuplicate=Copy-Json $probeIssue
    Write-CoordinationJsonAtomic -Path (Join-Path $context.IssuesPath "historical-$($probeIssue.fingerprint).json") -Document $historicalDuplicate
    $openLedger=@(Get-EnvironmentIssueLedgerItems -Context $context -WorktreeId $context.WorktreeId -Capability @('DEV_RUNTIME'))
    Assert-ManagedOperation ($openLedger.Count -eq 1 -and $openLedger[0].status -eq 'OPEN') `
        'duplicate historical ledger entries were not collapsed to one canonical OPEN issue'
    $resolved=Resolve-EnvironmentIssueByManagedOperation -Issue $openLedger[0] -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($resolved.status -eq 'FIXED') 'probe-only issue was not closed by matching managed operation'
    Assert-ManagedOperation ($resolved.resolutionEvidence.resolutionKind -eq 'MANAGED_OPERATION_SUPERSESSION') `
        'managed operation resolution was not typed'
    Assert-ManagedOperation ($resolved.resolutionEvidence.issueCallerKind -eq 'sandbox' -and `
        $resolved.resolutionEvidence.operationCallerKind -eq 'host') 'probe and operation callers were not independently audited'
    Assert-ManagedOperation (Test-EnvironmentIssueResolutionEvidence -Issue $resolved -Context $context -RepoRoot $repoRoot) `
        'durable managed-operation resolution evidence was rejected'
    $firstResolution=Copy-Json $resolved
    $idempotentResolution=Resolve-EnvironmentIssueByManagedOperation -Issue $historicalDuplicate -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($idempotentResolution.status -eq 'FIXED') `
        'identical managed-operation resolution retry did not preserve the FIXED result'
    Assert-ManagedOperation ($idempotentResolution.resolutionEvidence.resolvedAt -eq $firstResolution.resolutionEvidence.resolvedAt -and `
        $idempotentResolution.lastSeen -eq $firstResolution.lastSeen -and `
        $idempotentResolution.occurrences -eq $firstResolution.occurrences) `
        'identical managed-operation resolution retry rewrote an already closed ledger item'
    $closedLedger=@(Get-EnvironmentIssueLedgerItems -Context $context -WorktreeId $context.WorktreeId -Capability @('DEV_RUNTIME'))
    Assert-ManagedOperation ($closedLedger.Count -eq 1 -and $closedLedger[0].status -eq 'FIXED') `
        'duplicate historical ledger entries did not retain one canonical FIXED issue after rerun'
    $idempotentReview=[pscustomobject][ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;outcome='PASS';openCount=0
        issues=@($closedLedger|ForEach-Object{[pscustomobject]@{fingerprint=$_.fingerprint;classification=$_.status}})
    }
    Assert-ManagedOperation ($idempotentReview.schemaVersion -eq 4 -and $idempotentReview.outcome -eq 'PASS' -and `
        $idempotentReview.openCount -eq 0 -and $idempotentReview.issues.Count -eq 1) `
        'immediate identical rerun did not retain schema v4 PASS/openCount=0 semantics'
    $wrongGenerationResolutionRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalDuplicate -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-b -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$wrongGenerationResolutionRejected=$true}
    Assert-ManagedOperation $wrongGenerationResolutionRejected 'idempotent resolution accepted a receipt for the wrong generation'
    $wrongCallerIssue=Copy-Json $historicalDuplicate;$wrongCallerIssue.callerKind='host'
    $wrongCallerResolutionRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $wrongCallerIssue -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$wrongCallerResolutionRejected=$true}
    Assert-ManagedOperation $wrongCallerResolutionRejected 'idempotent resolution accepted a mismatched issue caller'
    $wrongCapabilityIssue=Copy-Json $historicalDuplicate;$wrongCapabilityIssue.capability='LINE_E2E'
    $wrongCapabilityResolutionRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $wrongCapabilityIssue -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$wrongCapabilityResolutionRejected=$true}
    Assert-ManagedOperation $wrongCapabilityResolutionRejected 'idempotent resolution accepted a mismatched issue capability'

    $env:CODEX_SANDBOX='1'
    try {
        $dockerProbeIssue=Write-EnvironmentIssue -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DOCKER_TEST `
            -Expected ready -Actual denied -RecheckKind MANAGED_OPERATION -Participation PROBE_ONLY `
            -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    } finally {
        if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    }
    $dockerResolved=Resolve-EnvironmentIssueByManagedOperation -Issue $dockerProbeIssue -ReceiptPath $dockerReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($dockerResolved.status -eq 'FIXED' -and `
        $dockerResolved.resolutionEvidence.operation -eq 'DOCKER_SHARED_INFRASTRUCTURE_READY') `
        'probe-only Docker issue was not superseded by exact managed shared-infrastructure evidence'

    $env:CODEX_SANDBOX='1'
    try {
        $dockerParticipant=Write-EnvironmentIssue -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DOCKER_TEST `
            -Expected ready -Actual denied -RecheckKind MANAGED_OPERATION -Participation OPERATION_PARTICIPANT `
            -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    } finally {
        if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    }
    $dockerParticipantRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $dockerParticipant -ReceiptPath $dockerReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$dockerParticipantRejected=$true}
    Assert-ManagedOperation $dockerParticipantRejected 'Docker operation participant was silently superseded by a host receipt'
    $crossCapabilityDocker=Test-EnvironmentManagedOperationReceipt -ReceiptPath $dockerReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $crossCapabilityDocker.Ready) 'Docker observation receipt unlocked runtime capability'
    $preflightText=Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-preflight.ps1') -Raw -Encoding UTF8
    Assert-ManagedOperation ($preflightText.Contains("@('DOCKER_TEST','DEV_RUNTIME','LINE_E2E')")) `
        'DOCKER_TEST probe-only access denial was not routed to managed-operation recheck'
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
    $expiredResolutionRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalDuplicate -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$expiredResolutionRejected=$true}
    Assert-ManagedOperation $expiredResolutionRejected 'idempotent resolution accepted an expired managed operation receipt'
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $document

    $arbitrary=Copy-Json $document;$arbitrary.operationCallerKind='sandbox';$arbitrary.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $arbitrary
    Write-CoordinationJsonAtomic -Path $runtimeReceipt.Path -Document $arbitrary
    $badCaller=Test-EnvironmentManagedOperationReceipt -ReceiptPath $runtimeReceipt.Path -Capability DEV_RUNTIME `
        -Operation RUNTIME_START -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop
    Assert-ManagedOperation (-not $badCaller.Ready) 'sandbox caller forged a host managed operation receipt'
    $forgedCallerResolutionRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalDuplicate -ReceiptPath $runtimeReceipt.Path `
        -Generation generation-a -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop|Out-Null}catch{$forgedCallerResolutionRejected=$true}
    Assert-ManagedOperation $forgedCallerResolutionRejected 'idempotent resolution accepted a non-host operation caller receipt'
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

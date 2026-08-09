[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scriptsRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')
$repoRoot=Split-Path -Parent $scriptsRoot
$stateRoot=Join-Path ([IO.Path]::GetTempPath()) ("mms-managed-operation-"+[guid]::NewGuid().ToString('n').Substring(0,8))
$renewalStateRoot=Join-Path ([IO.Path]::GetTempPath()) ("mms-managed-renewal-"+[guid]::NewGuid().ToString('n').Substring(0,8))
$assertions=0
$previousSandbox=$env:CODEX_SANDBOX
function Assert-ManagedOperation([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:assertions++}
function New-ReadyProbe {[pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;Version='fake'}}
function Copy-Json($Value){$Value|ConvertTo-Json -Depth 20|ConvertFrom-Json}
function New-HistoricalManagedFixture {
    param(
        [Parameter(Mandatory)]$CurrentReceipt,[Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$Code,[Parameter(Mandatory)][string]$Capability,
        [Parameter(Mandatory)][string]$Operation,[Parameter(Mandatory)][string]$OldContract,
        [Parameter(Mandatory)][string]$OldGeneration,[string]$CallerKind='sandbox'
    )
    $receipt=Copy-Json $CurrentReceipt
    $receipt.nonce=[guid]::NewGuid().ToString('n')
    $issued=[DateTimeOffset]::UtcNow.AddMinutes(-20)
    $receipt.issuedAt=$issued.ToString('o');$receipt.expiresAt=$issued.AddMinutes(15).ToString('o')
    $receipt.contractFingerprint=$OldContract;$receipt.generation=$OldGeneration
    $receipt.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $receipt
    $receiptPath=Join-Path $Context.ManagedOperationReceiptsPath "$($receipt.nonce).json"
    Write-CoordinationJsonAtomic -Path $receiptPath -Document $receipt
    $resolvedAt=$issued.AddMinutes(1).ToString('o')
    $fingerprint=Get-EnvironmentIssueFingerprint -Code $Code -Capability $Capability `
        -CallerKind $CallerKind -WorktreeId $Context.WorktreeId
    $issue=[pscustomobject][ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;fingerprint=$fingerprint;code=$Code;capability=$Capability
        repoId=$Context.RepoId;worktreeId=$Context.WorktreeId;callerKind=$CallerKind
        firstSeen=$receipt.issuedAt;lastSeen=$resolvedAt;occurrences=1;expected='ready';actual='denied'
        observationContractFingerprint=$OldContract;contractFingerprint=$OldContract
        recheckKind='MANAGED_OPERATION';participation='PROBE_ONLY';status='FIXED'
        resolutionEvidence=[pscustomobject][ordered]@{
            kind='FIXED';resolutionKind='MANAGED_OPERATION_SUPERSESSION';resolvedAt=$resolvedAt
            callerKind=$CallerKind;issueCallerKind=$CallerKind;operationCallerKind='host'
            capability=$Capability;operation=$Operation;generation=$OldGeneration
            worktreeId=$Context.WorktreeId;contractFingerprint=$OldContract
            snapshotSequence=[long]$receipt.snapshotSequence;state='MATCH'
            receiptFingerprint=$receipt.receiptFingerprint;authorityClass='REVIEW_OBSERVATION_ONLY'
        }
    }
    $issuePath=Join-Path $Context.IssuesPath "$fingerprint.json"
    Write-CoordinationJsonAtomic -Path $issuePath -Document $issue
    return [pscustomobject]@{Issue=$issue;IssuePath=$issuePath;Receipt=$receipt;ReceiptPath=$receiptPath}
}

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

    Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue
    $renewalHostRuntime=New-EnvironmentSnapshot -Capability DEV_RUNTIME -RepoRoot $repoRoot -StateRoot $renewalStateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $renewalRuntimeReceipt=New-EnvironmentManagedOperationReceipt -Capability DEV_RUNTIME -Operation RUNTIME_START `
        -Generation generation-current -Snapshot $renewalHostRuntime -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    $renewalContext=Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    $renewalFingerprint=Get-EnvironmentIssueFingerprint -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DEV_RUNTIME `
        -CallerKind sandbox -WorktreeId $renewalContext.WorktreeId
    $historicalReceipt=Copy-Json $renewalRuntimeReceipt.Receipt
    $historicalReceipt.nonce=[guid]::NewGuid().ToString('n')
    $historicalIssuedAt=[DateTimeOffset]::UtcNow.AddMinutes(-20)
    $historicalReceipt.issuedAt=$historicalIssuedAt.ToString('o')
    $historicalReceipt.expiresAt=$historicalIssuedAt.AddMinutes(15).ToString('o')
    $historicalReceipt.contractFingerprint=$contractWithoutDocker
    $historicalReceipt.generation='generation-old'
    $historicalReceipt.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $historicalReceipt
    $historicalReceiptPath=Join-Path $renewalContext.ManagedOperationReceiptsPath "$($historicalReceipt.nonce).json"
    Write-CoordinationJsonAtomic -Path $historicalReceiptPath -Document $historicalReceipt
    $historicalResolvedAt=[DateTimeOffset]::Parse([string]$historicalReceipt.issuedAt).AddMinutes(1).ToString('o')
    $historicalFixedIssue=[pscustomobject][ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;fingerprint=$renewalFingerprint
        code='PREFLIGHT_CALLER_ACCESS_DENIED';capability='DEV_RUNTIME'
        repoId=$renewalContext.RepoId;worktreeId=$renewalContext.WorktreeId;callerKind='sandbox'
        firstSeen=$historicalReceipt.issuedAt;lastSeen=$historicalResolvedAt;occurrences=1
        expected='ready';actual='denied';observationContractFingerprint=$contractWithoutDocker
        contractFingerprint=$contractWithoutDocker;recheckKind='MANAGED_OPERATION';participation='PROBE_ONLY';status='FIXED'
        resolutionEvidence=[pscustomobject][ordered]@{
            kind='FIXED';resolutionKind='MANAGED_OPERATION_SUPERSESSION';resolvedAt=$historicalResolvedAt
            callerKind='sandbox';issueCallerKind='sandbox';operationCallerKind='host'
            capability='DEV_RUNTIME';operation='RUNTIME_START';generation='generation-old'
            worktreeId=$renewalContext.WorktreeId;contractFingerprint=$contractWithoutDocker
            snapshotSequence=[long]$historicalReceipt.snapshotSequence;state='MATCH'
            receiptFingerprint=$historicalReceipt.receiptFingerprint;authorityClass='REVIEW_OBSERVATION_ONLY'
        }
    }
    Write-CoordinationJsonAtomic -Path (Join-Path $renewalContext.IssuesPath "$renewalFingerprint.json") -Document $historicalFixedIssue
    $renewed=Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
        -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($renewed.status -eq 'FIXED' -and $renewed.contractFingerprint -eq $contractWithDocker) `
        'historical FIXED managed evidence was not renewed to the current contract'
    Assert-ManagedOperation ($renewed.resolutionEvidence.generation -eq 'generation-current' -and `
        $renewed.resolutionEvidence.receiptFingerprint -eq $renewalRuntimeReceipt.Receipt.receiptFingerprint) `
        'historical FIXED managed evidence was not renewed to the current generation and receipt'
    Assert-ManagedOperation ($renewed.observationContractFingerprint -eq $contractWithoutDocker -and `
        @($renewed.managedOperationResolutionHistory).Count -eq 1 -and `
        $renewed.managedOperationResolutionHistory[0].receiptFingerprint -eq $historicalReceipt.receiptFingerprint -and `
        $renewed.resolutionEvidence.supersedesReceiptFingerprint -eq $historicalReceipt.receiptFingerprint) `
        'historical managed renewal did not preserve observation contract and audit lineage'
    Assert-ManagedOperation (Test-Path -LiteralPath $historicalReceiptPath -PathType Leaf) `
        'historical managed renewal deleted the superseded receipt'

    $historicalIssuePath=Join-Path $renewalContext.IssuesPath "$renewalFingerprint.json"
    foreach($case in @(
        @{Name='issue caller';Apply={param($item)$item.callerKind='host'}},
        @{Name='issue capability';Apply={param($item)$item.capability='LINE_E2E'}},
        @{Name='issue participation';Apply={param($item)$item.participation='OPERATION_PARTICIPANT'}},
        @{Name='unapproved issue code';Apply={param($item)$item.code='UNAPPROVED_CALLER_DENIAL'}},
        @{Name='old evidence caller';Apply={param($item)$item.resolutionEvidence.issueCallerKind='host'}},
        @{Name='old evidence capability';Apply={param($item)$item.resolutionEvidence.capability='LINE_E2E'}},
        @{Name='old evidence operation';Apply={param($item)$item.resolutionEvidence.operation='LINE_CONNECTIVITY_PROBE'}},
        @{Name='old evidence generation';Apply={param($item)$item.resolutionEvidence.generation='generation-tampered'}},
        @{Name='old evidence contract';Apply={param($item)$item.resolutionEvidence.contractFingerprint='000000000000000000000000'}},
        @{Name='old evidence snapshot';Apply={param($item)$item.resolutionEvidence.snapshotSequence=[long]$item.resolutionEvidence.snapshotSequence+1}},
        @{Name='old evidence authority';Apply={param($item)$item.resolutionEvidence.authorityClass='OPERATION_PARTICIPANT'}}
    )){
        $candidate=Copy-Json $historicalFixedIssue
        & $case.Apply $candidate
        Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $candidate
        $rejected=$false
        try {Resolve-EnvironmentIssueByManagedOperation -Issue $candidate -ReceiptPath $renewalRuntimeReceipt.Path `
            -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop|Out-Null}catch{$rejected=$true}
        Assert-ManagedOperation $rejected "historical renewal accepted wrong $($case.Name)"
    }

    $currentReceiptDocument=Copy-Json $renewalRuntimeReceipt.Receipt
    foreach($case in @(
        @{Name='operation';Apply={param($item)$item.operation='LINE_CONNECTIVITY_PROBE'}},
        @{Name='contract';Apply={param($item)$item.contractFingerprint='000000000000000000000000'}},
        @{Name='authority';Apply={param($item)$item.authorityClass='OPERATION_PARTICIPANT'}},
        @{Name='caller';Apply={param($item)$item.operationCallerKind='sandbox'}}
    )){
        Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $historicalFixedIssue
        $candidate=Copy-Json $currentReceiptDocument
        & $case.Apply $candidate
        $candidate.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $candidate
        Write-CoordinationJsonAtomic -Path $renewalRuntimeReceipt.Path -Document $candidate
        $rejected=$false
        try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
            -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop|Out-Null}catch{$rejected=$true}
        Assert-ManagedOperation $rejected "historical renewal accepted wrong current receipt $($case.Name)"
    }
    Write-CoordinationJsonAtomic -Path $renewalRuntimeReceipt.Path -Document $currentReceiptDocument
    foreach($case in @(
        @{Name='repository';Apply={param($item)$item.repoId='wrong-repository'}},
        @{Name='worktree';Apply={param($item)$item.worktreeId='wrong-worktree'}},
        @{Name='machine';Apply={param($item)$item.machineAlias='wrong-machine'}},
        @{Name='historical receipt authority';Apply={param($item)$item.authorityClass='OPERATION_PARTICIPANT'}}
    )){
        Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $historicalFixedIssue
        $candidate=Copy-Json $historicalReceipt
        & $case.Apply $candidate
        Write-CoordinationJsonAtomic -Path $historicalReceiptPath -Document $candidate
        $rejected=$false
        try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
            -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop|Out-Null}catch{$rejected=$true}
        Assert-ManagedOperation $rejected "historical renewal accepted wrong $($case.Name)"
    }
    Write-CoordinationJsonAtomic -Path $historicalReceiptPath -Document $historicalReceipt
    Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $historicalFixedIssue
    $expiredCurrent=Copy-Json $currentReceiptDocument
    $expiredIssued=[DateTimeOffset]::UtcNow.AddMinutes(-30)
    $expiredCurrent.issuedAt=$expiredIssued.ToString('o');$expiredCurrent.expiresAt=$expiredIssued.AddMinutes(15).ToString('o')
    $expiredCurrent.receiptFingerprint=Get-EnvironmentManagedOperationFingerprint -Receipt $expiredCurrent
    Write-CoordinationJsonAtomic -Path $renewalRuntimeReceipt.Path -Document $expiredCurrent
    $expiredCurrentRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
        -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop|Out-Null}catch{$expiredCurrentRejected=$true}
    Assert-ManagedOperation $expiredCurrentRejected 'historical renewal accepted an expired current receipt'
    Write-CoordinationJsonAtomic -Path $renewalRuntimeReceipt.Path -Document $currentReceiptDocument
    Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $historicalFixedIssue
    $wrongCurrentGenerationRejected=$false
    try {Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
        -Generation generation-wrong -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop|Out-Null}catch{$wrongCurrentGenerationRejected=$true}
    Assert-ManagedOperation $wrongCurrentGenerationRejected 'historical renewal accepted the wrong current generation'

    Write-CoordinationJsonAtomic -Path $historicalIssuePath -Document $renewed
    $renewedRerun=Resolve-EnvironmentIssueByManagedOperation -Issue $historicalFixedIssue -ReceiptPath $renewalRuntimeReceipt.Path `
        -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($renewedRerun.resolutionEvidence.resolvedAt -eq $renewed.resolutionEvidence.resolvedAt -and `
        @($renewedRerun.managedOperationResolutionHistory).Count -eq 1) `
        'immediate current-receipt renewal rerun was not idempotent'

    Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue
    $renewalHostLine=New-EnvironmentSnapshot -Capability LINE_E2E -RepoRoot $repoRoot -StateRoot $renewalStateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $renewalLineReceipt=New-EnvironmentManagedOperationReceipt -Capability LINE_E2E -Operation LINE_CONNECTIVITY_PROBE `
        -Generation generation-current -Snapshot $renewalHostLine -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    $lineFixture=New-HistoricalManagedFixture -CurrentReceipt $renewalLineReceipt.Receipt -Context $renewalContext `
        -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability LINE_E2E -Operation LINE_CONNECTIVITY_PROBE `
        -OldContract $contractWithoutDocker -OldGeneration generation-old
    $lineRenewed=Resolve-EnvironmentIssueByManagedOperation -Issue $lineFixture.Issue -ReceiptPath $renewalLineReceipt.Path `
        -Generation generation-current -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($lineRenewed.status -eq 'FIXED' -and $lineRenewed.contractFingerprint -eq $contractWithDocker -and `
        $lineRenewed.resolutionEvidence.generation -eq 'generation-current') `
        'LINE_E2E historical managed evidence was not renewed'

    $renewalHostDocker=New-EnvironmentSnapshot -Capability DOCKER_TEST -RepoRoot $repoRoot -StateRoot $renewalStateRoot `
        -MachineAlias test-laptop -ProbeOverrides $overrides
    $renewalDockerReceipt=New-EnvironmentManagedOperationReceipt -Capability DOCKER_TEST `
        -Operation DOCKER_SHARED_INFRASTRUCTURE_READY -Generation generation-current -Snapshot $renewalHostDocker `
        -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    $env:CODEX_SANDBOX='1'
    try {
        $sandboxDockerReady=New-EnvironmentSnapshot -Capability DOCKER_TEST -RepoRoot $repoRoot -StateRoot $renewalStateRoot `
            -MachineAlias test-laptop -ProbeOverrides $overrides
        $renewalDockerOpen=Write-EnvironmentIssue -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DOCKER_TEST `
            -Expected ready -Actual denied -RecheckKind MANAGED_OPERATION -Participation PROBE_ONLY `
            -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
        $sharedStoppedOpen=Write-EnvironmentIssue -Code PREFLIGHT_SHARED_CONTAINER_STOPPED -Capability DOCKER_TEST `
            -Expected ready -Actual stopped -RecheckKind CAPABILITY -Participation PROBE_ONLY `
            -RepoRoot $repoRoot -StateRoot $renewalStateRoot -MachineAlias test-laptop
    } finally {
        if($null -eq $previousSandbox){Remove-Item Env:CODEX_SANDBOX -ErrorAction SilentlyContinue}else{$env:CODEX_SANDBOX=$previousSandbox}
    }
    $renewalDockerFixed=Resolve-EnvironmentIssueByManagedOperation -Issue $renewalDockerOpen `
        -ReceiptPath $renewalDockerReceipt.Path -Generation generation-current -RepoRoot $repoRoot `
        -StateRoot $renewalStateRoot -MachineAlias test-laptop
    $sharedStoppedFixed=Resolve-EnvironmentIssue -Code PREFLIGHT_SHARED_CONTAINER_STOPPED -Capability DOCKER_TEST `
        -Status FIXED -CallerKind sandbox -Snapshot $sandboxDockerReady -RepoRoot $repoRoot `
        -StateRoot $renewalStateRoot -MachineAlias test-laptop
    Assert-ManagedOperation ($renewalDockerFixed.status -eq 'FIXED' -and $sharedStoppedFixed.status -eq 'FIXED') `
        'current Docker OPEN issues were not resolved after historical renewals'

    $renewalLedger=@(Get-EnvironmentIssueLedgerItems -Context $renewalContext -WorktreeId $renewalContext.WorktreeId `
        -Capability @('DEV_RUNTIME','LINE_E2E','DOCKER_TEST'))
    $renewalIssueReviews=@($renewalLedger|ForEach-Object{
        [pscustomobject]@{fingerprint=$_.fingerprint;classification=if(Test-EnvironmentIssueResolutionEvidence `
            -Issue $_ -Context $renewalContext -RepoRoot $repoRoot){[string]$_.status}else{'OPEN'}}
    })
    $renewalReview=[pscustomobject][ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion
        outcome=if(@($renewalIssueReviews|Where-Object{$_.classification -eq 'OPEN'}).Count -eq 0){'PASS'}else{'BLOCKED'}
        openCount=@($renewalIssueReviews|Where-Object{$_.classification -eq 'OPEN'}).Count
        issues=$renewalIssueReviews
    }
    Assert-ManagedOperation ($renewalLedger.Count -eq 4 -and `
        @($renewalIssueReviews|Where-Object{$_.classification -eq 'FIXED'}).Count -eq 4) `
        'historical renewals and current OPEN resolutions did not leave four canonical FIXED issues'
    Assert-ManagedOperation ($renewalReview.schemaVersion -eq 4 -and $renewalReview.outcome -eq 'PASS' -and `
        $renewalReview.openCount -eq 0) `
        'historical renewal sequence did not finish with schema v4 PASS/openCount=0'

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
    if(Test-Path -LiteralPath $renewalStateRoot){Remove-Item -LiteralPath $renewalStateRoot -Recurse -Force}
}

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\tooling-owner-identity'
$stateRoot = Join-Path $root 'coordination'
$generation = 'generation-current'
$resource = $null
$assertions = 0
$matrix = [Collections.Generic.List[object]]::new()
$matrixFailures = [Collections.Generic.List[string]]::new()

function Assert-OwnerIdentity([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-OwnerSnapshot([int]$Id, [datetimeoffset]$StartedAt, [string]$TargetWorktree = $worktree) {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $TargetWorktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$StartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
}

function New-Operation([int]$OwnerPid, [datetimeoffset]$PublishedAt, [string]$Status = 'ACTIVE') {
    return [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status=$Status
        ownerPid=$OwnerPid;startedAt=$PublishedAt.ToString('o');resources=@($resource)
    }
}

function New-TypedOperation([int]$OwnerPid, [datetimeoffset]$OwnerStartedAt, [datetimeoffset]$PublishedAt, [string]$Status = 'ACTIVE') {
    $operation = New-Operation -OwnerPid $OwnerPid -PublishedAt $PublishedAt -Status $Status
    $operation | Add-Member -NotePropertyName ownerProcessIdentity -NotePropertyValue ([pscustomobject]@{
        schema='MMS_COORDINATION_OWNER_V1';processId=$OwnerPid
        startedAt=(Format-CoordinationCanonicalProcessStartTime $OwnerStartedAt)
        canonicalPrecision='UTC_MICROSECOND_TRUNCATED'
    })
    return $operation
}

function New-Query([string]$Outcome, $Snapshot = $null, [string]$ReasonCode = $null) {
    $document = [ordered]@{Outcome=$Outcome;Snapshot=$Snapshot}
    if ($ReasonCode) { $document['ReasonCode'] = $ReasonCode }
    return [pscustomobject]$document
}

try {
    [IO.Directory]::CreateDirectory($worktree) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    $definition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $worktree
    $resource = $definition.Resource

    $currentStarted = [datetimeoffset]::Parse('2026-08-10T14:40:00.0000000Z')
    $currentSnapshot = New-OwnerSnapshot -Id 456992 -StartedAt $currentStarted
    $receiptId = [guid]::NewGuid().ToString()
    $receipt = [ordered]@{
        operationId=$receiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=456992;processStartedAt=(Format-ManagedProcessCanonicalStartTime $currentStarted)
        worktree=[IO.Path]::GetFullPath($worktree).TrimEnd('\');resource=$resource;generation=$generation
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $currentSnapshot -Worktree $worktree -Component SpringBoot -ExactActiveCoordinationProof)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $currentSnapshot -Worktree $worktree -Component SpringBoot -ExactActiveCoordinationProof)
        disposition='managed-runtime; evidence-retained'
    }
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt $receipt
    $state = [pscustomobject]@{
        springBootPid=456992;springBootOwnershipReceiptId=$receiptId;serviceGeneration=$generation
    }

    $deadPid = 7001
    $reusedPid = 7840
    $competitorPid = 7003
    $currentOperation = New-Operation -OwnerPid 456992 -PublishedAt $currentStarted.AddSeconds(1)
    $deadOperation = New-Operation -OwnerPid $deadPid -PublishedAt ([datetimeoffset]::Parse('2026-08-02T10:00:00Z'))
    $reusedOperation = New-Operation -OwnerPid $reusedPid -PublishedAt ([datetimeoffset]::Parse('2026-08-02T11:00:00Z'))
    $competitorOperation = New-Operation -OwnerPid $competitorPid -PublishedAt ([datetimeoffset]::Parse('2026-08-10T14:35:00Z'))
    $reusedSnapshot = New-OwnerSnapshot -Id $reusedPid -StartedAt ([datetimeoffset]::Parse('2026-08-10T12:00:00Z'))
    $competitorSnapshot = New-OwnerSnapshot -Id $competitorPid -StartedAt ([datetimeoffset]::Parse('2026-08-10T14:30:00Z'))
    $query = {
        param($id)
        switch ([int]$id) {
            456992 { return New-Query READY $currentSnapshot }
            7001 { return New-Query NOT_FOUND }
            7840 { return New-Query READY $reusedSnapshot }
            7003 { return New-Query READY $competitorSnapshot }
            default { return New-Query UNKNOWN $null 'PROCESS_IDENTITY_UNAVAILABLE' }
        }
    }

    foreach ($case in @(
        @{Name='current-only';Expected='MANAGED_ACTIVE';Operations=@($currentOperation)},
        @{Name='dead-plus-current';Expected='MANAGED_ACTIVE';Operations=@($deadOperation,$currentOperation)},
        @{Name='pid-reuse-plus-current';Expected='MANAGED_ACTIVE';Operations=@($reusedOperation,$currentOperation)},
        @{Name='live-competitor-plus-current';Expected='ORPHAN_BLOCKED_COMPETING_OWNER';Operations=@($competitorOperation,$currentOperation)}
    )) {
        $diagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
            -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments $case.Operations
        $matrix.Add([pscustomobject]@{name=$case.Name;expected=$case.Expected;actual=$diagnosis.Classification})
        if ($diagnosis.Classification -eq $case.Expected) { $assertions++ }
        else { $matrixFailures.Add("$($case.Name): expected=$($case.Expected), actual=$($diagnosis.Classification)") }
    }
    if ($matrixFailures.Count -gt 0) { throw ($matrixFailures -join '; ') }

    Assert-OwnerIdentity ($deadOperation.status -eq 'ACTIVE' -and $reusedOperation.status -eq 'ACTIVE') `
        'Read-only diagnosis mutated historical operation evidence.'

    $accessDeniedOperation = New-Operation -OwnerPid 7004 -PublishedAt ([datetimeoffset]::Parse('2026-08-10T14:36:00Z'))
    $accessDeniedQuery = {
        param($id)
        if ([int]$id -eq 456992) { return New-Query READY $currentSnapshot }
        return New-Query CALLER_ACCESS_DENIED $null 'OWNER_PROCESS_QUERY_ACCESS_DENIED'
    }
    $accessDenied = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $accessDeniedQuery -OperationDocuments @($accessDeniedOperation,$currentOperation)
    Assert-OwnerIdentity ($accessDenied.Classification -eq 'ORPHAN_UNVERIFIABLE' -and
            $accessDenied.ReasonCode -eq 'COORDINATION_OWNER_IDENTITY_UNAVAILABLE') `
        'Access-denied owner identity did not fail closed.'

    $incompleteQuery = {
        param($id)
        if ([int]$id -eq 456992) { return New-Query READY $currentSnapshot }
        return New-Query READY ([pscustomobject]@{ProcessId=[int]$id})
    }
    $incomplete = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $incompleteQuery -OperationDocuments @($accessDeniedOperation,$currentOperation)
    Assert-OwnerIdentity ($incomplete.Classification -eq 'ORPHAN_UNVERIFIABLE') `
        'Incomplete owner identity did not fail closed.'

    $malformed = New-Operation -OwnerPid 7005 -PublishedAt ([datetimeoffset]::Parse('2026-08-10T14:36:00Z'))
    $malformed.ownerPid = '7005'
    $malformedResult = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($malformed,$currentOperation)
    Assert-OwnerIdentity ($malformedResult.Classification -eq 'ORPHAN_UNVERIFIABLE' -and
            $malformedResult.ReasonCode -eq 'COORDINATION_OWNER_EVIDENCE_INVALID') `
        'Malformed operation evidence did not fail closed.'

    $typedCurrent = New-TypedOperation -OwnerPid 456992 -OwnerStartedAt $currentStarted `
        -PublishedAt $currentStarted.AddSeconds(1)
    $typedCurrentResult = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($typedCurrent)
    Assert-OwnerIdentity ($typedCurrentResult.Classification -eq 'MANAGED_ACTIVE') `
        'Exact typed current owner was not accepted.'

    $typedReused = New-TypedOperation -OwnerPid $reusedPid `
        -OwnerStartedAt ([datetimeoffset]::Parse('2026-08-02T10:59:59Z')) `
        -PublishedAt ([datetimeoffset]::Parse('2026-08-02T11:00:00Z'))
    $typedReusedResult = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($typedReused,$typedCurrent)
    Assert-OwnerIdentity ($typedReusedResult.Classification -eq 'MANAGED_ACTIVE') `
        'Typed PID reuse incorrectly blocked the exact current owner.'

    $publicationStart = [datetimeoffset]::UtcNow.AddMinutes(-1)
    $publicationResource = New-CoordinationResource -Type 'worktree/maven-target' -Key 'owner-publication' -Mode Exclusive
    $published = Enter-CoordinationOperation -Resources @($publicationResource) -StateRoot $stateRoot `
        -OwnerProcessQuery ({param($id) [pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{
            ProcessId=[int]$id;StartedAtUtc=$publicationStart.UtcDateTime
        }}}).GetNewClosure()
    try {
        $publishedManifest = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $published.OperationId
        Assert-OwnerIdentity ($publishedManifest.ownerProcessIdentity.schema -eq 'MMS_COORDINATION_OWNER_V1' -and
                $publishedManifest.ownerProcessIdentity.processId -eq $PID -and
                $publishedManifest.ownerProcessIdentity.canonicalPrecision -eq 'UTC_MICROSECOND_TRUNCATED') `
            'New operation publication did not retain typed canonical owner identity.'
    } finally { Exit-CoordinationOperation $published }
    $restarted = Enter-CoordinationOperation -Resources @($publicationResource) -StateRoot $stateRoot `
        -OwnerProcessQuery ({param($id) [pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{
            ProcessId=[int]$id;StartedAtUtc=$publicationStart.UtcDateTime
        }}}).GetNewClosure()
    try {
        $releasedPublication = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $published.OperationId
        $restartManifest = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $restarted.OperationId
        Assert-OwnerIdentity ($releasedPublication.status -eq 'RELEASED' -and $restartManifest.status -eq 'ACTIVE' -and
                $restarted.OperationId -ne $published.OperationId) `
            'Stop/restart did not publish a distinct current operation generation.'
    } finally { Exit-CoordinationOperation $restarted }

    foreach ($operation in @($deadOperation,$typedReused,$typedCurrent)) {
        Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $stateRoot $operation.operationId) -Document $operation
    }
    $durableOperations = @(Get-ManagedCoordinationOperations -StateRoot $stateRoot)
    $durableDiagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments $durableOperations
    Assert-OwnerIdentity ($durableDiagnosis.Classification -eq 'MANAGED_ACTIVE' -and
            @($durableDiagnosis.StaleOperations).Count -eq 2) `
        'Durable mixed legacy operations did not isolate both stale owners.'
    Assert-OwnerIdentity ((Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $deadOperation.operationId).status -eq 'ACTIVE') `
        'Read-only durable diagnosis changed stale operation status.'

    $reconciled = Complete-ManagedStaleCoordinationOperations -Classifications $durableDiagnosis.StaleOperations `
        -StateRoot $stateRoot -ExpectedResource $resource -ProcessQuery $query
    $deadTerminal = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $deadOperation.operationId
    $reuseTerminal = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $typedReused.operationId
    Assert-OwnerIdentity ($reconciled.ChangedCount -eq 2 -and $deadTerminal.status -eq 'ABANDONED' -and
            $reuseTerminal.status -eq 'ABANDONED' -and
            $deadTerminal.reconcileEvidence.schema -eq 'MMS_COORDINATION_RECONCILE_V1') `
        'Formal reconcile did not durably retain typed stale-owner evidence.'
    $replayed = Complete-ManagedStaleCoordinationOperations -Classifications $durableDiagnosis.StaleOperations `
        -StateRoot $stateRoot -ExpectedResource $resource -ProcessQuery $query
    Assert-OwnerIdentity ($replayed.ChangedCount -eq 0) 'Formal reconcile replay was not exactly-once.'

    $afterReconcile = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery $query
    Assert-OwnerIdentity ($afterReconcile.Classification -eq 'MANAGED_ACTIVE') `
        'Current exact owner did not remain active after stale operation reconcile.'

    $tamperedTerminal = New-TypedOperation -OwnerPid 7010 -OwnerStartedAt $currentStarted.AddMinutes(-2) `
        -PublishedAt $currentStarted.AddMinutes(-1) -Status ABANDONED
    $tamperedTerminal | Add-Member -NotePropertyName reconcileEvidence -NotePropertyValue ([pscustomobject]@{
        schema='MMS_COORDINATION_RECONCILE_V1';classification='STALE_DEAD';reasonCode='OWNER_PROCESS_NOT_FOUND'
        observedAt=$currentStarted.ToString('o');disposition='tampered'
    })
    Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $stateRoot $tamperedTerminal.operationId) -Document $tamperedTerminal
    $tamperedReplay = Resolve-CoordinationStaleOperation -StateRoot $stateRoot -OperationId $tamperedTerminal.operationId `
        -ExpectedResource $resource -ProcessQuery $query
    Assert-OwnerIdentity ($tamperedReplay.Outcome -eq 'BLOCKED' -and
            $tamperedReplay.ReasonCode -eq 'OPERATION_TYPED_EVIDENCE_INVALID') `
        'Tampered terminal reconcile evidence was accepted on replay.'

    [pscustomobject]@{status='passed';assertions=$assertions;matrix=@($matrix);externalMutations=0} | ConvertTo-Json -Depth 6 -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

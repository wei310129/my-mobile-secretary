[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\tooling-legacy-stop-order'
$stateRoot = Join-Path $root 'coordination'
$stateFile = Join-Path $worktree 'scripts\.dev-state.json'
$failures = [Collections.Generic.List[string]]::new()
$assertions = 0

function Assert-LegacyStopOrder([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-StopOrderSnapshot([int]$Id, [datetimeoffset]$StartedAt) {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $worktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$StartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
}

try {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $stateFile)) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))

    $safeLegacyId = 'b3-durable-integration-closure-20260730-r1'
    $safeLegacyPath = Get-CoordinationOperationPath -StateRoot $stateRoot -OperationId $safeLegacyId
    $safeLegacy = [ordered]@{
        schemaVersion=1;operationId=$safeLegacyId;status='RELEASED';ownerPid=8101
        startedAt='2026-07-30T10:00:00.0000000Z';resources=@('v1/environment/out-of-scope')
        releasedAt='2026-07-30T10:05:00.0000000Z'
    }
    Write-CoordinationJsonAtomic -Path $safeLegacyPath -Document $safeLegacy
    $before = [IO.File]::ReadAllText($safeLegacyPath, [Text.Encoding]::UTF8)
    $legacyScanError = $null
    $legacyDocuments = @()
    try { $legacyDocuments = @(Get-ManagedCoordinationOperations -StateRoot $stateRoot) }
    catch { $legacyScanError = $_.Exception.Message }
    $after = [IO.File]::ReadAllText($safeLegacyPath, [Text.Encoding]::UTF8)
    if ($legacyScanError -or $legacyDocuments.Count -ne 1 -or $before -ne $after) {
        $failures.Add("safe-named-released: expected=ACCEPTED_READ_ONLY, actual=COORDINATION_EVIDENCE_INVALID")
    } else { $assertions++ }

    $legacyDead = [pscustomobject]@{
        schemaVersion=1;operationId='legacy-active-dead-r1';status='ACTIVE';ownerPid=8111
        startedAt='2026-07-30T11:00:00.0000000Z';resources=@('v1/environment/legacy-target')
    }
    $legacyReuse = [pscustomobject]@{
        schemaVersion=1;operationId='legacy_active_reuse.r2';status='ACTIVE';ownerPid=8112
        startedAt='2026-07-30T11:00:00.0000000Z';resources=@('v1/environment/legacy-target')
    }
    $legacyLive = [pscustomobject]@{
        schemaVersion=1;operationId='legacy-live-owner-r3';status='ACTIVE';ownerPid=8113
        startedAt='2026-07-30T11:00:00.0000000Z';resources=@('v1/environment/legacy-target')
    }
    foreach ($legacy in @($legacyDead,$legacyReuse,$legacyLive)) {
        Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $stateRoot $legacy.operationId) -Document $legacy
    }
    $legacyQuery = {
        param($id)
        switch ([int]$id) {
            8111 { return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null} }
            8112 { return [pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{
                ProcessId=8112;StartedAtUtc=[datetime]::Parse('2026-08-10T12:00:00Z')
            }} }
            8113 { return [pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{
                ProcessId=8113;StartedAtUtc=[datetime]::Parse('2026-07-30T10:59:00Z')
            }} }
        }
    }
    $deadClass = Get-CoordinationOperationOwnerClassification -Manifest $legacyDead `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    Assert-LegacyStopOrder ($deadClass.State -eq 'STALE_DEAD') 'Safe named dead legacy owner was not classified stale.'
    $deadReconcile = Resolve-CoordinationStaleOperation -StateRoot $stateRoot -OperationId $legacyDead.operationId `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    $deadTerminal = Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $legacyDead.operationId
    Assert-LegacyStopOrder ($deadReconcile.Changed -and $deadTerminal.status -eq 'ABANDONED' -and
            $deadTerminal.reconcileEvidence.schema -eq 'MMS_COORDINATION_RECONCILE_V1') `
        'Safe named dead legacy operation did not retain formal reconcile evidence.'
    $deadReplay = Resolve-CoordinationStaleOperation -StateRoot $stateRoot -OperationId $legacyDead.operationId `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    Assert-LegacyStopOrder ($deadReplay.Outcome -eq 'READY' -and -not $deadReplay.Changed) `
        'Safe named dead legacy reconcile was not exactly-once.'

    $reuseClass = Get-CoordinationOperationOwnerClassification -Manifest $legacyReuse `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    Assert-LegacyStopOrder ($reuseClass.State -eq 'STALE_PID_REUSED') `
        'Safe named reused legacy PID was not classified stale.'
    $reuseReconcile = Resolve-CoordinationStaleOperation -StateRoot $stateRoot -OperationId $legacyReuse.operationId `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    Assert-LegacyStopOrder ($reuseReconcile.Changed -and
            (Get-CoordinationOperationManifest -StateRoot $stateRoot -OperationId $legacyReuse.operationId).status -eq 'ABANDONED') `
        'Safe named reused legacy operation was not formally reconciled.'

    $liveClass = Get-CoordinationOperationOwnerClassification -Manifest $legacyLive `
        -ExpectedResource 'v1/environment/legacy-target' -ProcessQuery $legacyQuery
    $liveSet = Get-ManagedCoordinationOperationSet -Operations @($legacyLive) `
        -ExpectedResource 'v1/environment/legacy-target' -CurrentProcessId 8999 -ProcessQuery $legacyQuery
    Assert-LegacyStopOrder ($liveClass.State -eq 'ACTIVE_LEGACY_BOUNDED' -and $liveSet.Competitors.Count -eq 1) `
        'Genuine live safe named legacy owner did not remain a bounded competitor.'

    $publicationStart = [datetimeoffset]::UtcNow.AddMinutes(-1)
    $publication = Enter-CoordinationOperation -Resources @(
        (New-CoordinationResource -Type environment -Key 'legacy-id-publication' -Mode Exclusive)
    ) -StateRoot $stateRoot -OwnerProcessQuery ({param($id)[pscustomobject]@{
        Outcome='READY';Snapshot=[pscustomobject]@{ProcessId=[int]$id;StartedAtUtc=$publicationStart.UtcDateTime}
    }}).GetNewClosure()
    try {
        Assert-LegacyStopOrder ($publication.OperationId -match '^[a-f0-9-]{36}$') `
            'New typed publication did not retain GUID operation identity.'
    } finally { Exit-CoordinationOperation $publication }

    foreach ($invalidId in @('slash/id','back\slash','two..dots','C:\absolute','/absolute','',('x' * 129),42)) {
        Assert-LegacyStopOrder (-not (Test-CoordinationOperationId -Value $invalidId)) `
            'Unsafe, blank, overlong, or wrong-type operation id was accepted.'
    }

    $mismatchRoot = Join-Path $root 'filename-mismatch'
    $mismatchPath = Join-Path (Join-Path $mismatchRoot 'operations') 'filename-safe.json'
    $mismatchDocument = [ordered]@{
        schemaVersion=1;operationId='different-safe-id';status='RELEASED';ownerPid=8114
        startedAt='2026-07-30T11:00:00Z';resources=@('v1/environment/out-of-scope');releasedAt='2026-07-30T11:01:00Z'
    }
    Write-CoordinationJsonAtomic -Path $mismatchPath -Document $mismatchDocument
    $mismatchRejected = $false
    try { Get-ManagedCoordinationOperations -StateRoot $mismatchRoot | Out-Null } catch { $mismatchRejected = $true }
    Assert-LegacyStopOrder $mismatchRejected 'Filename and operationId mismatch was accepted.'

    $invalidJsonRoot = Join-Path $root 'invalid-json'
    $invalidJsonPath = Join-Path (Join-Path $invalidJsonRoot 'operations') 'invalid-json-id.json'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $invalidJsonPath)) | Out-Null
    [IO.File]::WriteAllText($invalidJsonPath, '{invalid', [Text.UTF8Encoding]::new($false))
    $invalidJsonRejected = $false
    try { Get-ManagedCoordinationOperations -StateRoot $invalidJsonRoot | Out-Null } catch { $invalidJsonRejected = $true }
    Assert-LegacyStopOrder $invalidJsonRejected 'Invalid operation JSON was accepted.'

    $malformedRoot = Join-Path $root 'malformed-active'
    $malformedPath = Join-Path (Join-Path $malformedRoot 'operations') 'malformed-active-id.json'
    Write-CoordinationJsonAtomic -Path $malformedPath -Document ([ordered]@{
        schemaVersion=1;operationId='malformed-active-id';status='ACTIVE';ownerPid='8115'
        startedAt='2026-07-30T11:00:00Z';resources=@('v1/environment/legacy-target')
    })
    $malformedRejected = $false
    try { Get-ManagedCoordinationOperations -StateRoot $malformedRoot | Out-Null } catch { $malformedRejected = $true }
    Assert-LegacyStopOrder $malformedRejected 'Malformed relevant ACTIVE evidence was accepted.'

    $started = [datetimeoffset]::Parse('2026-08-11T02:00:00.0000000Z')
    $currentPid = 8201
    $stalePid = 8202
    $snapshot = New-StopOrderSnapshot -Id $currentPid -StartedAt $started
    $definition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $worktree
    $receiptId = [guid]::NewGuid().ToString()
    $receipt = [ordered]@{
        operationId=$receiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=$currentPid;processStartedAt=(Format-ManagedProcessCanonicalStartTime $started)
        worktree=[IO.Path]::GetFullPath($worktree).TrimEnd('\');resource=$definition.Resource;generation='generation-current'
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $snapshot -Worktree $worktree -Component SpringBoot -ExactActiveCoordinationProof)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $snapshot -Worktree $worktree -Component SpringBoot -ExactActiveCoordinationProof)
        disposition='managed-runtime; evidence-retained'
    }
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt $receipt
    Write-CoordinationJsonAtomic -Path $stateFile -Document ([ordered]@{
        springBootPid=$currentPid;springBootOwnershipReceiptId=$receiptId;serviceGeneration='generation-current'
    })
    $currentOperation = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=$currentPid
        startedAt=$started.AddSeconds(1).ToString('o');resources=@($definition.Resource)
    }
    $staleOperation = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=$stalePid
        startedAt=$started.AddMinutes(-5).ToString('o');resources=@($definition.Resource)
        EvidencePath=(Join-Path (Join-Path $stateRoot 'operations') 'missing-stale-evidence.json')
    }
    $query = {
        param($id)
        if ([int]$id -eq $currentPid) { return [pscustomobject]@{Outcome='READY';Snapshot=$snapshot} }
        if ([int]$id -eq $stalePid) { return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null} }
        return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'}
    }
    $stopCalls = 0
    $stopResult = Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
        -ProcessQuery $query -OperationDocuments @($currentOperation,$staleOperation) -StopAdapter {
            param($proof);$script:stopCalls++;[pscustomobject]@{Success=$true}
        }
    $stateAfter = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($stopCalls -ne 0 -or $stopResult.Outcome -ne 'BLOCKED' -or [int]$stateAfter.springBootPid -ne $currentPid) {
        $failures.Add("stop-preflight-order: expected=BLOCKED/stopCalls=0, actual=$($stopResult.Outcome)/stopCalls=$stopCalls")
    } else { $assertions++ }

    $competitorOperation = [pscustomobject]@{
        schemaVersion=1;operationId='safe-live-competitor';status='ACTIVE';ownerPid=8210
        startedAt=$started.AddSeconds(1).ToString('o');resources=@($definition.Resource)
    }
    foreach ($blockedCase in @(
        @{Label='genuine competitor';Competitor=[pscustomobject]@{Outcome='READY';Snapshot=(New-StopOrderSnapshot -Id 8210 -StartedAt $started)}},
        @{Label='access denied';Competitor=[pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';Snapshot=$null;ReasonCode='PROCESS_QUERY_ACCESS_DENIED'}},
        @{Label='identity incomplete';Competitor=[pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{ProcessId=8210}}}
    )) {
        $caseQuery = {
            param($id)
            if ([int]$id -eq $currentPid) { return [pscustomobject]@{Outcome='READY';Snapshot=$snapshot} }
            return $blockedCase.Competitor
        }.GetNewClosure()
        $caseStopCalls = 0
        $blockedStop = Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
            -ProcessQuery $caseQuery -OperationDocuments @($currentOperation,$competitorOperation) -StopAdapter {
                param($proof);$script:caseStopCalls++;[pscustomobject]@{Success=$true}
            }
        Assert-LegacyStopOrder ($blockedStop.Outcome -eq 'BLOCKED' -and $caseStopCalls -eq 0) `
            "$($blockedCase.Label) reached the process mutation boundary."
    }

    $boundaryQueries = 0
    $boundaryStopCalls = 0
    $boundaryQuery = {
        param($id)
        $script:boundaryQueries++
        $boundarySnapshot = if ($script:boundaryQueries -ge 4) {
            [pscustomobject]@{
                ProcessId=$currentPid;ParentProcessId=1;Name='powershell.exe'
                ExecutablePath=$snapshot.ExecutablePath;CommandLine=$snapshot.CommandLine
                StartedAtUtc=$started.AddMinutes(1).UtcDateTime;QueryKind='FIXTURE_EXACT'
            }
        } else { $snapshot }
        return [pscustomobject]@{Outcome='READY';Snapshot=$boundarySnapshot}
    }
    $boundaryResult = Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
        -ProcessQuery $boundaryQuery -OperationDocuments @($currentOperation) -StopAdapter {
            param($proof);$script:boundaryStopCalls++;[pscustomobject]@{Success=$true}
        }
    Assert-LegacyStopOrder ($boundaryResult.Outcome -eq 'BLOCKED' -and
            $boundaryResult.ReasonCode -eq 'STOP_MUTATION_BOUNDARY_CHANGED' -and $boundaryStopCalls -eq 0) `
        'Exact current owner was not revalidated immediately before process mutation.'

    $successRoot = Join-Path $root 'successful-stop'
    $successWorktree = Join-Path $successRoot 'project\var\worktrees\tooling-success'
    $successStateRoot = Join-Path $successRoot 'coordination'
    $successStateFile = Join-Path $successWorktree 'scripts\.dev-state.json'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $successStateFile)) | Out-Null
    [IO.File]::WriteAllText((Join-Path $successWorktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    $successPid = 8301
    $successStalePid = 8302
    $successStarted = [datetimeoffset]::Parse('2026-08-10T03:00:00Z')
    $successSnapshot = [pscustomobject]@{
        ProcessId=$successPid;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $successWorktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$successStarted.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
    $successDefinition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $successWorktree
    $successReceiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $successStateRoot -Receipt ([ordered]@{
        operationId=$successReceiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=$successPid;processStartedAt=(Format-ManagedProcessCanonicalStartTime $successStarted)
        worktree=[IO.Path]::GetFullPath($successWorktree).TrimEnd('\');resource=$successDefinition.Resource;generation='generation-success'
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $successSnapshot -Worktree $successWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $successSnapshot -Worktree $successWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        disposition='managed-runtime; evidence-retained'
    })
    Write-CoordinationJsonAtomic -Path $successStateFile -Document ([ordered]@{
        springBootPid=$successPid;springBootOwnershipReceiptId=$successReceiptId;serviceGeneration='generation-success'
    })
    $successCurrent = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=$successPid
        startedAt=$successStarted.AddSeconds(1).ToString('o');resources=@($successDefinition.Resource)
    }
    $successStale = [pscustomobject]@{
        schemaVersion=1;operationId='legacy-success-stale-r1';status='ACTIVE';ownerPid=$successStalePid
        startedAt=$successStarted.AddMinutes(-5).ToString('o');resources=@($successDefinition.Resource)
    }
    foreach ($operation in @($successCurrent,$successStale)) {
        Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $successStateRoot $operation.operationId) -Document $operation
    }
    $script:successRunning = $true
    $script:successStopCalls = 0
    $successQuery = {
        param($id)
        if ([int]$id -eq $successPid) {
            if ($script:successRunning) { return [pscustomobject]@{Outcome='READY';Snapshot=$successSnapshot} }
            return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
        }
        if ([int]$id -eq $successStalePid) { return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null} }
        return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'}
    }
    $successResult = Invoke-ManagedComponentStop -Worktree $successWorktree -Component SpringBoot `
        -StateRoot $successStateRoot -ProcessQuery $successQuery -StopAdapter {
            param($proof);$script:successStopCalls++;$script:successRunning=$false;[pscustomobject]@{Success=$true}
        }
    $successState = [IO.File]::ReadAllText($successStateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $successCurrentTerminal = Get-CoordinationOperationManifest -StateRoot $successStateRoot -OperationId $successCurrent.operationId
    $successStaleTerminal = Get-CoordinationOperationManifest -StateRoot $successStateRoot -OperationId $successStale.operationId
    $successStopReceipt = [IO.File]::ReadAllText((Join-Path (Join-Path $successStateRoot 'receipts') "$($successResult.ReceiptId).json"), [Text.Encoding]::UTF8) | ConvertFrom-Json
    $successRecovery = @(Get-ChildItem (Join-Path $successStateRoot 'receipts') -Filter '*.json' -File | ForEach-Object {
        [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
    } | Where-Object action -eq 'managed-component-stop-recovery')
    Assert-LegacyStopOrder ($successResult.Outcome -eq 'READY' -and $script:successStopCalls -eq 1 -and
            -not $successState.springBootPid) 'Successful managed stop did not clear exact state after one process mutation.'
    Assert-LegacyStopOrder ($successCurrentTerminal.status -eq 'RELEASED' -and $successStaleTerminal.status -eq 'ABANDONED') `
        'Successful managed stop did not release current and abandon stale coordination evidence.'
    Assert-LegacyStopOrder ($successStopReceipt.action -eq 'managed-process-stop' -and
            $successRecovery.Count -eq 1 -and $successRecovery[0].status -eq 'COMPLETED') `
        'Successful managed stop did not retain completion and recovery receipts.'
    $successReplay = Invoke-ManagedComponentStop -Worktree $successWorktree -Component SpringBoot `
        -StateRoot $successStateRoot -ProcessQuery $successQuery -StopAdapter {
            param($proof);$script:successStopCalls++;[pscustomobject]@{Success=$true}
        }
    Assert-LegacyStopOrder ($successReplay.Outcome -eq 'READY' -and -not $successReplay.Changed -and
            $script:successStopCalls -eq 1) 'Successful managed stop replay was not exactly-once.'

    $recoveryRoot = Join-Path $root 'completion-recovery'
    $recoveryWorktree = Join-Path $recoveryRoot 'project\var\worktrees\tooling-recovery'
    $recoveryStateRoot = Join-Path $recoveryRoot 'coordination'
    $recoveryStateFile = Join-Path $recoveryWorktree 'scripts\.dev-state.json'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $recoveryStateFile)) | Out-Null
    [IO.File]::WriteAllText((Join-Path $recoveryWorktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    $recoveryPid = 8401
    $recoveryStarted = [datetimeoffset]::Parse('2026-08-10T04:00:00Z')
    $recoverySnapshot = [pscustomobject]@{
        ProcessId=$recoveryPid;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $recoveryWorktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$recoveryStarted.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
    $recoveryDefinition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $recoveryWorktree
    $recoveryStartReceiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $recoveryStateRoot -Receipt ([ordered]@{
        operationId=$recoveryStartReceiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=$recoveryPid;processStartedAt=(Format-ManagedProcessCanonicalStartTime $recoveryStarted)
        worktree=[IO.Path]::GetFullPath($recoveryWorktree).TrimEnd('\');resource=$recoveryDefinition.Resource;generation='generation-recovery'
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $recoverySnapshot -Worktree $recoveryWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $recoverySnapshot -Worktree $recoveryWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        disposition='managed-runtime; evidence-retained'
    })
    Write-CoordinationJsonAtomic -Path $recoveryStateFile -Document ([ordered]@{
        springBootPid=$recoveryPid;springBootOwnershipReceiptId=$recoveryStartReceiptId;serviceGeneration='generation-recovery'
    })
    $recoveryOperation = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=$recoveryPid
        startedAt=$recoveryStarted.AddSeconds(1).ToString('o');resources=@($recoveryDefinition.Resource)
    }
    Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $recoveryStateRoot $recoveryOperation.operationId) -Document $recoveryOperation
    $script:recoveryRunning = $true
    $script:recoveryStopCalls = 0
    $recoveryQuery = {
        param($id)
        if ($script:recoveryRunning) { return [pscustomobject]@{Outcome='READY';Snapshot=$recoverySnapshot} }
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
    }
    $recoveryFailure = Invoke-ManagedComponentStop -Worktree $recoveryWorktree -Component SpringBoot `
        -StateRoot $recoveryStateRoot -ProcessQuery $recoveryQuery -StopAdapter {
            param($proof);$script:recoveryStopCalls++;$script:recoveryRunning=$false;[pscustomobject]@{Success=$true}
        } -CompletionAdapter { param($proof,$worktree,$stateFile,$stateRoot,$receiptId) throw 'fixture completion failure' }
    $recoveryStateAfterFailure = [IO.File]::ReadAllText($recoveryStateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $recoveryContract = [IO.File]::ReadAllText((Join-Path (Join-Path $recoveryStateRoot 'receipts') "$($recoveryFailure.RecoveryReceiptId).json"), [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-LegacyStopOrder ($recoveryFailure.Outcome -eq 'RECOVERY_REQUIRED' -and
            $recoveryFailure.ReasonCode -eq 'STOP_DURABLE_COMPLETION_REQUIRED' -and
            $recoveryContract.status -eq 'RECOVERY_REQUIRED' -and [int]$recoveryStateAfterFailure.springBootPid -eq $recoveryPid) `
        'Post-stop completion failure did not retain a typed durable recovery contract.'
    $recoveryReplay = Invoke-ManagedComponentStop -Worktree $recoveryWorktree -Component SpringBoot `
        -StateRoot $recoveryStateRoot -ProcessQuery $recoveryQuery -StopAdapter {
            param($proof);$script:recoveryStopCalls++;[pscustomobject]@{Success=$true}
        }
    $recoveryStateAfterReplay = [IO.File]::ReadAllText($recoveryStateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $recoveryContractAfterReplay = [IO.File]::ReadAllText((Join-Path (Join-Path $recoveryStateRoot 'receipts') "$($recoveryFailure.RecoveryReceiptId).json"), [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-LegacyStopOrder ($recoveryReplay.Outcome -eq 'READY' -and -not $recoveryStateAfterReplay.springBootPid -and
            $script:recoveryStopCalls -eq 1 -and $recoveryContractAfterReplay.status -eq 'COMPLETED') `
        'Official replay did not converge post-stop durable completion exactly-once.'

    $stateFailurePid = 8402
    $stateFailureStarted = [datetimeoffset]::Parse('2026-08-10T05:00:00Z')
    $stateFailureSnapshot = [pscustomobject]@{
        ProcessId=$stateFailurePid;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $recoveryWorktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$stateFailureStarted.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
    $stateFailureStartReceiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $recoveryStateRoot -Receipt ([ordered]@{
        operationId=$stateFailureStartReceiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=$stateFailurePid;processStartedAt=(Format-ManagedProcessCanonicalStartTime $stateFailureStarted)
        worktree=[IO.Path]::GetFullPath($recoveryWorktree).TrimEnd('\');resource=$recoveryDefinition.Resource;generation='generation-state-failure'
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $stateFailureSnapshot -Worktree $recoveryWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $stateFailureSnapshot -Worktree $recoveryWorktree -Component SpringBoot -ExactActiveCoordinationProof)
        disposition='managed-runtime; evidence-retained'
    })
    Write-CoordinationJsonAtomic -Path $recoveryStateFile -Document ([ordered]@{
        springBootPid=$stateFailurePid;springBootOwnershipReceiptId=$stateFailureStartReceiptId;serviceGeneration='generation-state-failure'
    })
    $stateFailureOperation = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=$stateFailurePid
        startedAt=$stateFailureStarted.AddSeconds(1).ToString('o');resources=@($recoveryDefinition.Resource)
    }
    Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $recoveryStateRoot $stateFailureOperation.operationId) -Document $stateFailureOperation
    $script:stateFailureRunning = $true
    $script:stateFailureStopCalls = 0
    $stateFailureQuery = {
        param($id)
        if ($script:stateFailureRunning) { return [pscustomobject]@{Outcome='READY';Snapshot=$stateFailureSnapshot} }
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
    }
    $stateFailure = Invoke-ManagedComponentStop -Worktree $recoveryWorktree -Component SpringBoot `
        -StateRoot $recoveryStateRoot -ProcessQuery $stateFailureQuery -StopAdapter {
            param($proof);$script:stateFailureStopCalls++;$script:stateFailureRunning=$false;[pscustomobject]@{Success=$true}
        } -CompletionAdapter {
            param($proof,$worktree,$stateFile,$stateRoot,$receiptId)
            Complete-ManagedComponentStop -Proof $proof -Worktree $worktree -StateFile "$stateFile.missing" `
                -StateRoot $stateRoot -ReceiptId $receiptId
        }
    $stateFailureState = [IO.File]::ReadAllText($recoveryStateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $stateFailureTerminal = Get-CoordinationOperationManifest -StateRoot $recoveryStateRoot -OperationId $stateFailureOperation.operationId
    if (-not $stateFailure.PSObject.Properties['RecoveryReceiptId']) {
        throw ("State-write fixture did not reach durable recovery: {0}" -f ($stateFailure | ConvertTo-Json -Compress -Depth 6))
    }
    $stateFailureRecovery = [IO.File]::ReadAllText((Join-Path (Join-Path $recoveryStateRoot 'receipts') "$($stateFailure.RecoveryReceiptId).json"), [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-LegacyStopOrder ($stateFailure.Outcome -eq 'RECOVERY_REQUIRED' -and
            $stateFailure.ReasonCode -eq 'STOP_DURABLE_COMPLETION_REQUIRED' -and
            [int]$stateFailureState.springBootPid -eq $stateFailurePid -and
            $stateFailureTerminal.status -eq 'RELEASED' -and $stateFailureRecovery.status -eq 'RECOVERY_REQUIRED') `
        'Post-stop state write failure did not retain exact released ownership and typed recovery evidence.'
    $stateFailureReplay = Invoke-ManagedComponentStop -Worktree $recoveryWorktree -Component SpringBoot `
        -StateRoot $recoveryStateRoot -ProcessQuery $stateFailureQuery -StopAdapter {
            param($proof);$script:stateFailureStopCalls++;[pscustomobject]@{Success=$true}
        }
    $stateFailureStateAfterReplay = [IO.File]::ReadAllText($recoveryStateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-LegacyStopOrder ($stateFailureReplay.Outcome -eq 'READY' -and
            -not $stateFailureStateAfterReplay.springBootPid -and $script:stateFailureStopCalls -eq 1) `
        'Official replay did not safely converge a post-stop state write failure.'

    if ($failures.Count -gt 0) { throw ($failures -join '; ') }
    [pscustomobject]@{status='passed';assertions=$assertions;externalMutations=0} | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

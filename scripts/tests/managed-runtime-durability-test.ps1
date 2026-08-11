[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\tooling-runtime'
$otherWorktree = Join-Path $root 'project\var\worktrees\other'
$stateRoot = Join-Path $root 'coordination'
$generation = 'generation-current'
$started = [datetimeoffset]::UtcNow.AddSeconds(-2)
$assertions = 0

function Assert-Durability([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-ProcessSnapshot(
    [int]$Id = 901,
    [string]$TargetWorktree = $worktree,
    [datetimeoffset]$StartedAt = $started,
    [string]$Application = 'root'
) {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $TargetWorktree\scripts\coordinated-maven-run.ps1 -Application $Application"
        StartedAtUtc=$StartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
}

function New-QueryResult([string]$Outcome, $Snapshot = $null, [string]$ReasonCode = $null) {
    $result = [ordered]@{Outcome=$Outcome;Snapshot=$Snapshot}
    if ($ReasonCode) { $result['ReasonCode'] = $ReasonCode }
    return [pscustomobject]$result
}

try {
    [IO.Directory]::CreateDirectory($worktree) | Out-Null
    [IO.Directory]::CreateDirectory($otherWorktree) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $otherWorktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))

    $snapshot = New-ProcessSnapshot
    $ready = New-QueryResult READY $snapshot
    $missing = New-QueryResult NOT_FOUND
    $denied = New-QueryResult CALLER_ACCESS_DENIED $null 'PROCESS_QUERY_ACCESS_DENIED'
    $unknown = New-QueryResult UNKNOWN $null 'PROCESS_IDENTITY_UNAVAILABLE'

    $durableSnapshot = New-ProcessSnapshot -Id 890
    $durableReady = New-QueryResult READY $durableSnapshot
    $durableLaunch = Start-ManagedDurableProcess -Worktree $worktree -Component SpringBoot `
        -ExecutablePath 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' `
        -Arguments @('-NoProfile','-File',(Join-Path $worktree 'scripts\coordinated-maven-run.ps1'),'-Application','root') `
        -Generation $generation -StateRoot $stateRoot -StandardOutputPath (Join-Path $worktree 'scripts\.logs\spring.out.log') `
        -StandardErrorPath (Join-Path $worktree 'scripts\.logs\spring.err.log') `
        -LaunchAdapter {param($request)[pscustomobject]@{ProcessId=890;DurabilityClass='WINDOWS_JOB_BREAKAWAY'}} `
        -ProcessQuery {param($id)$durableReady} `
        -ReadinessProbe {param($phase)[pscustomobject]@{Ready=$true;Code='ACTUATOR_UP'}}
    Assert-Durability ($durableLaunch.ProcessId -eq 890 -and $durableLaunch.ReceiptId -match '^[a-f0-9-]{36}$') `
        'an exact breakaway launch did not pass the durable publication contract'

    $ordinaryLaunchRejected = $false
    try {
        Start-ManagedDurableProcess -Worktree $worktree -Component SpringBoot `
            -ExecutablePath 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' `
            -Arguments @('-NoProfile','-File',(Join-Path $worktree 'scripts\coordinated-maven-run.ps1'),'-Application','root') `
            -Generation $generation -StateRoot $stateRoot -StandardOutputPath (Join-Path $worktree 'scripts\.logs\ordinary.out.log') `
            -StandardErrorPath (Join-Path $worktree 'scripts\.logs\ordinary.err.log') `
            -LaunchAdapter {param($request)[pscustomobject]@{ProcessId=891;DurabilityClass='CALLER_JOB_CHILD'}} `
            -ProcessQuery {param($id)$durableReady} `
            -ReadinessProbe {param($phase)[pscustomobject]@{Ready=$true;Code='ACTUATOR_UP'}} | Out-Null
    } catch { $ordinaryLaunchRejected = $_.Exception.Message -match 'durable breakaway' }
    Assert-Durability $ordinaryLaunchRejected 'an ordinary caller-job child was accepted as durable'

    $observations = @(
        @{Result=$ready;Expected='UP'},
        @{Result=$missing;Expected='DOWN'},
        @{Result=$denied;Expected='CALLER_ACCESS_DENIED'},
        @{Result=$unknown;Expected='UNKNOWN'}
    )
    foreach ($case in $observations) {
        $classification = Get-ManagedProcessObservation -QueryResult $case.Result
        Assert-Durability ($classification.State -eq $case.Expected) "query result was not classified as $($case.Expected)"
    }

    $bannerOnlyRejected = $false
    try {
        Confirm-ManagedRuntimePublication -Worktree $worktree -Component SpringBoot -ProcessId 901 `
            -Generation $generation -StateRoot $stateRoot -ProcessQuery {param($id)$ready} `
            -ReadinessProbe {param($phase)[pscustomobject]@{Ready=$false;Code='ACTUATOR_NOT_READY'}} | Out-Null
    } catch { $bannerOnlyRejected = $_.Exception.Message -match 'readiness' }
    Assert-Durability $bannerOnlyRejected 'a banner-only Spring process published success without actuator readiness'

    $publicationQueries = [Collections.Queue]::new()
    $publicationQueries.Enqueue($ready)
    $publicationQueries.Enqueue($missing)
    $reclaimedRejected = $false
    $reclaimedError = $null
    try {
        Confirm-ManagedRuntimePublication -Worktree $worktree -Component SpringBoot -ProcessId 901 `
            -Generation $generation -StateRoot $stateRoot `
            -ProcessQuery ({param($id)$publicationQueries.Dequeue()}).GetNewClosure() `
            -ReadinessProbe {param($phase)[pscustomobject]@{Ready=$true;Code='ACTUATOR_UP'}} | Out-Null
    } catch { $reclaimedError=$_.Exception.Message;$reclaimedRejected = $reclaimedError -match 'publication boundary' }
    Assert-Durability $reclaimedRejected "a child reclaimed at the publication boundary retained false success: $reclaimedError"

    $receiptFiles = @(Get-ChildItem (Join-Path $stateRoot 'receipts') -Filter '*.json' -File -ErrorAction SilentlyContinue)
    $revoked = @($receiptFiles | ForEach-Object {
        [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
    } | Where-Object { $_.action -eq 'managed-process-start' -and $_.status -eq 'REVOKED' })
    Assert-Durability ($revoked.Count -eq 1) 'publication-boundary disappearance left a READY ownership receipt'

    $ngrokQueries = [Collections.Queue]::new()
    $ngrokSnapshot = [pscustomobject]@{
        ProcessId=902;ParentProcessId=1;Name='ngrok.exe';ExecutablePath='C:\tools\ngrok.exe'
        CommandLine='"C:\tools\ngrok.exe" http --url=safe.example.invalid http://localhost:8080 --log=stdout --log-level=warn'
        StartedAtUtc=$started.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
    $ngrokQueries.Enqueue((New-QueryResult READY $ngrokSnapshot))
    $ngrokQueries.Enqueue($missing)
    $ngrokRejected = $false
    try {
        Confirm-ManagedRuntimePublication -Worktree $worktree -Component Ngrok -ProcessId 902 `
            -Generation $generation -StateRoot $stateRoot `
            -ExpectedExecutablePath 'C:\tools\ngrok.exe' `
            -ExpectedArguments @('http','--url=safe.example.invalid','http://localhost:8080','--log=stdout','--log-level=warn') `
            -ExpectedPort 8080 -LaunchObservedAtUtc $started `
            -ProcessQuery ({param($id)$ngrokQueries.Dequeue()}).GetNewClosure() `
            -ReadinessProbe {param($phase)[pscustomobject]@{Ready=$true;Code='TUNNEL_CONTRACT_MATCH'}} | Out-Null
    } catch { $ngrokRejected = $_.Exception.Message -match 'publication boundary' }
    Assert-Durability $ngrokRejected 'ngrok disappearance after publication retained success'

    $definition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $worktree
    $receiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt ([ordered]@{
        operationId=$receiptId;outcome='READY';status='READY';action='managed-process-start';component='SpringBoot'
        processId=901;processStartedAt=$started.ToString('o');worktree=[IO.Path]::GetFullPath($worktree).TrimEnd('\')
        resource=$definition.Resource;generation=$generation;commandContractFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $snapshot -Worktree $worktree -Component SpringBoot)
        ownershipIdentityFingerprint=(Get-ManagedProcessCommandFingerprint -Snapshot $snapshot -Worktree $worktree -Component SpringBoot)
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        disposition='managed-runtime; evidence-retained'
    })
    $state = [pscustomobject]@{
        springBootPid=901;springBootOwnershipReceiptId=$receiptId;serviceGeneration=$generation
    }
    $diagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery {param($id)$ready} -OperationDocuments @()
    Assert-Durability ($diagnosis.Classification -eq 'ORPHAN_EXACT_RECONCILABLE') `
        ("an exact owner with no active operation was not classified as a bounded orphan: {0}/{1}" -f `
            $diagnosis.Classification,$diagnosis.ReasonCode)

    $legacyState=[pscustomobject]@{
        springBootPid=901;serviceGeneration=$generation;startedAt=[datetimeoffset]::UtcNow.ToString('o')
        serviceLogDirectory=(Join-Path $worktree "scripts\.logs\generations\$generation")
        runtimeContentIdentity=[pscustomobject]@{serviceGeneration=$generation}
    }
    $legacyDiagnosis=Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $legacyState `
        -StateRoot $stateRoot -ProcessQuery {param($id)$ready} -OperationDocuments @()
    Assert-Durability ($legacyDiagnosis.Classification -eq 'ORPHAN_EXACT_RECONCILABLE' -and
            $legacyDiagnosis.EvidenceKind -eq 'LEGACY_DURABLE_STATE') `
        'a pre-receipt exact Spring owner with bounded generation evidence was not reconciliable'
    foreach($legacyCase in @(
        [pscustomobject]@{springBootPid=901;serviceGeneration='wrong-generation';startedAt=[datetimeoffset]::UtcNow.ToString('o');serviceLogDirectory=(Join-Path $worktree "scripts\.logs\generations\$generation");runtimeContentIdentity=[pscustomobject]@{serviceGeneration=$generation}},
        [pscustomobject]@{springBootPid=901;serviceGeneration=$generation;startedAt=$started.AddMinutes(-1).ToString('o');serviceLogDirectory=(Join-Path $worktree "scripts\.logs\generations\$generation");runtimeContentIdentity=[pscustomobject]@{serviceGeneration=$generation}},
        [pscustomobject]@{springBootPid=901;serviceGeneration=$generation;startedAt=[datetimeoffset]::UtcNow.ToString('o');serviceLogDirectory=(Join-Path $otherWorktree "scripts\.logs\generations\$generation");runtimeContentIdentity=[pscustomobject]@{serviceGeneration=$generation}}
    )) {
        $legacyBlocked=Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $legacyCase `
            -StateRoot $stateRoot -ProcessQuery {param($id)$ready} -OperationDocuments @()
        Assert-Durability ($legacyBlocked.Classification -eq 'ORPHAN_UNVERIFIABLE') `
            'legacy state with wrong generation, start window, or log identity authorized reconciliation'
    }
    $legacyStateFile=Join-Path $worktree 'scripts\.dev-state.json'
    Write-CoordinationJsonAtomic -Path $legacyStateFile -Document $legacyState
    $legacyStop=Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
        -ProcessQuery {param($id)$ready} -OperationDocuments @() `
        -StopAdapter {param($proof)[pscustomobject]@{Success=$true}}
    Assert-Durability ($legacyStop.Outcome -eq 'READY' -and $legacyStop.Disposition -eq 'ORPHAN_EXACT_RECONCILE') `
        'official managed stop did not reconcile the exact pre-receipt legacy Spring owner'
    $legacyStoppedState=[IO.File]::ReadAllText($legacyStateFile,[Text.Encoding]::UTF8)|ConvertFrom-Json
    Assert-Durability (-not $legacyStoppedState.springBootPid) 'legacy orphan reconcile did not converge durable state'

    $authority = New-ManagedOrphanStopAuthority -Diagnosis $diagnosis -StateRoot $stateRoot -TtlSeconds 30
    Assert-Durability ($authority.Action -eq 'managed-orphan-stop-authority' -and $authority.ExpiresAt -gt [datetimeoffset]::UtcNow) `
        'exact orphan diagnosis did not produce short-lived component-scoped authority'

    $validated = Test-ManagedOrphanStopAuthority -AuthorityId $authority.AuthorityId -Worktree $worktree `
        -Component SpringBoot -State $state -StateRoot $stateRoot -ProcessQuery {param($id)$ready}
    Assert-Durability ($validated.Outcome -eq 'READY' -and $validated.ProcessId -eq 901) `
        'fresh exact orphan stop authority was rejected'
    $wrongComponentAuthority = Test-ManagedOrphanStopAuthority -AuthorityId $authority.AuthorityId -Worktree $worktree `
        -Component Ngrok -State $state -StateRoot $stateRoot -ProcessQuery {param($id)$ready}
    Assert-Durability ($wrongComponentAuthority.Outcome -eq 'REJECTED' -and $wrongComponentAuthority.ReasonCode -eq 'COMPONENT_MISMATCH') `
        'component-scoped orphan authority was reused for another component'

    Complete-ManagedOrphanStopAuthority -AuthorityId $authority.AuthorityId -StateRoot $stateRoot
    $replay = Test-ManagedOrphanStopAuthority -AuthorityId $authority.AuthorityId -Worktree $worktree `
        -Component SpringBoot -State $state -StateRoot $stateRoot -ProcessQuery {param($id)$ready}
    Assert-Durability ($replay.Outcome -eq 'REJECTED' -and $replay.ReasonCode -eq 'AUTHORITY_ALREADY_CONSUMED') `
        'consumed orphan authority was replayable'

    $expiredAuthority = New-ManagedOrphanStopAuthority -Diagnosis $diagnosis -StateRoot $stateRoot -TtlSeconds 5
    $expiredPath=Join-Path (Join-Path $stateRoot 'receipts') "$($expiredAuthority.AuthorityId).json"
    $expiredDocument=[IO.File]::ReadAllText($expiredPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
    $expiredDocument.expiresAt=[datetimeoffset]::UtcNow.AddSeconds(-1).ToString('o')
    Write-CoordinationJsonAtomic -Path $expiredPath -Document $expiredDocument
    $expiredResult=Test-ManagedOrphanStopAuthority -AuthorityId $expiredAuthority.AuthorityId -Worktree $worktree `
        -Component SpringBoot -State $state -StateRoot $stateRoot -ProcessQuery {param($id)$ready}
    Assert-Durability ($expiredResult.Outcome -eq 'REJECTED' -and $expiredResult.ReasonCode -eq 'AUTHORITY_EXPIRED') `
        'expired orphan authority remained usable'

    $tamperedAuthority = New-ManagedOrphanStopAuthority -Diagnosis $diagnosis -StateRoot $stateRoot -TtlSeconds 30
    $tamperedPath=Join-Path (Join-Path $stateRoot 'receipts') "$($tamperedAuthority.AuthorityId).json"
    $tamperedDocument=[IO.File]::ReadAllText($tamperedPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
    $tamperedDocument.processId=999
    Write-CoordinationJsonAtomic -Path $tamperedPath -Document $tamperedDocument
    $tamperedResult=Test-ManagedOrphanStopAuthority -AuthorityId $tamperedAuthority.AuthorityId -Worktree $worktree `
        -Component SpringBoot -State $state -StateRoot $stateRoot -ProcessQuery {param($id)$ready}
    Assert-Durability ($tamperedResult.Outcome -eq 'REJECTED' -and $tamperedResult.ReasonCode -eq 'AUTHORITY_EVIDENCE_MISMATCH') `
        'tampered orphan authority remained usable'

    foreach ($case in @(
        @{Label='PID';State=[pscustomobject]@{springBootPid=999;springBootOwnershipReceiptId=$receiptId;serviceGeneration=$generation};Snapshot=$snapshot;Worktree=$worktree;Generation=$generation},
        @{Label='start time';State=$state;Snapshot=(New-ProcessSnapshot -StartedAt $started.AddMinutes(2));Worktree=$worktree;Generation=$generation},
        @{Label='worktree';State=$state;Snapshot=$snapshot;Worktree=$otherWorktree;Generation=$generation},
        @{Label='generation';State=[pscustomobject]@{springBootPid=901;springBootOwnershipReceiptId=$receiptId;serviceGeneration='generation-other'};Snapshot=$snapshot;Worktree=$worktree;Generation='generation-other'}
    )) {
        $caseResult = Get-ManagedOrphanDiagnosis -Worktree $case.Worktree -Component SpringBoot -State $case.State `
            -StateRoot $stateRoot -ProcessQuery ({param($id)New-QueryResult READY $case.Snapshot}).GetNewClosure() -OperationDocuments @()
        Assert-Durability ($caseResult.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') `
            "orphan reconciliation accepted a mismatched $($case.Label)"
    }

    $competitor = [pscustomobject]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=903;startedAt=$started.ToString('o')
        resources=@($definition.Resource)
    }
    $competitorSnapshot = New-ProcessSnapshot -Id 903 -StartedAt $started.AddSeconds(-1)
    $competing = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery {param($id)if($id -eq 901){$ready}else{[pscustomobject]@{Outcome='READY';Snapshot=$competitorSnapshot}}} `
        -OperationDocuments @($competitor)
    Assert-Durability ($competing.Classification -eq 'ORPHAN_BLOCKED_COMPETING_OWNER') `
        'an active competing owner did not fail closed'

    foreach ($case in @($denied,$unknown,$missing)) {
        $blocked = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
            -StateRoot $stateRoot -ProcessQuery ({param($id)$case}).GetNewClosure() -OperationDocuments @()
        Assert-Durability ($blocked.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') `
            'access-denied, unknown, or down process evidence authorized orphan reconciliation'
    }

    $serialized = @($competing,$replay) | ConvertTo-Json -Compress -Depth 8
    Assert-Durability ($serialized -notmatch [regex]::Escape($root) -and $serialized -notmatch 'safe\.example' -and
            $serialized -notmatch 'C:\\Windows|CommandLine|ExecutablePath') `
        'failure or reconciliation output leaked a host, account, path, command, or webhook value'

    $terminated=[Collections.Generic.List[int]]::new()
    $treeStop=Stop-ManagedExactProcessTree -RootProcessId 901 -ExpectedPort 8080 `
        -ProcessTableAdapter { [pscustomobject]@{Outcome='READY';Processes=@(
            [pscustomobject]@{ProcessId=901;ParentProcessId=1},
            [pscustomobject]@{ProcessId=910;ParentProcessId=901},
            [pscustomobject]@{ProcessId=911;ParentProcessId=910},
            [pscustomobject]@{ProcessId=999;ParentProcessId=1}
        )} } -TerminationAdapter {param($id)$terminated.Add([int]$id);[pscustomobject]@{Success=$true}} `
        -VerificationQuery {param($id)New-QueryResult NOT_FOUND} `
        -PortObservationAdapter {param($port)[pscustomobject]@{State='DOWN'}}
    Assert-Durability ($treeStop.Success -and $treeStop.StoppedCount -eq 3) 'exact managed tree did not stop and verify'
    Assert-Durability (($terminated -join ',') -eq '911,910,901' -and -not $terminated.Contains(999)) `
        'managed tree stop included an unrelated process or used the wrong descendant order'
    $deniedTree=Stop-ManagedExactProcessTree -RootProcessId 901 `
        -ProcessTableAdapter {[pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';Processes=@()}} `
        -TerminationAdapter {param($id)throw 'must not run'}
    Assert-Durability (-not $deniedTree.Success -and $deniedTree.ReasonCode -eq 'PROCESS_TREE_CALLER_ACCESS_DENIED') `
        'process-tree caller denial did not fail closed before mutation'
    $stopText=[IO.File]::ReadAllText((Join-Path $scripts 'dev-stop.ps1'),[Text.Encoding]::UTF8)
    Assert-Durability ($stopText.Contains('Stop-ManagedExactProcessTree') -and -not $stopText.Contains('Stop-ProcessTree -ProcessId $proof.ProcessId')) `
        'official stop still uses taskkill as its normal managed success path'

    [pscustomobject]@{status='passed';assertions=$assertions;liveProcesses='fake';externalMutations=0} |
        ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

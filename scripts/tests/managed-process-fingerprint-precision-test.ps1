[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\tooling-fingerprint'
$stateRoot = Join-Path $root 'coordination'
$generation = 'generation-precision'
$nativeStartedAt = [datetimeoffset]::Parse('2026-08-10T14:44:38.4765575Z')
$wmiStartedAt = [datetimeoffset]::Parse('2026-08-10T14:44:38.4765570Z')
$assertions = 0

function Assert-FingerprintPrecision([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-FingerprintSnapshot([datetimeoffset]$StartedAt, [int]$Id = 944) {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name='powershell.exe'
        ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
        CommandLine="powershell -File $worktree\scripts\coordinated-maven-run.ps1 -Application root"
        StartedAtUtc=$StartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'
    }
}

function New-QueryResult([string]$Outcome, $Snapshot = $null, [string]$ReasonCode = $null) {
    $result = [ordered]@{Outcome=$Outcome;Snapshot=$Snapshot}
    if ($ReasonCode) { $result['ReasonCode'] = $ReasonCode }
    return [pscustomobject]$result
}

function Copy-Receipt($Receipt) {
    return $Receipt | ConvertTo-Json -Depth 12 | ConvertFrom-Json
}

function Write-ReceiptVariant($Receipt, [scriptblock]$Apply) {
    $copy = Copy-Receipt $Receipt
    $copy.operationId = [guid]::NewGuid().ToString()
    & $Apply $copy
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt $copy
    return [string]$copy.operationId
}

try {
    [IO.Directory]::CreateDirectory($worktree) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))

    $nativeSnapshot = New-FingerprintSnapshot -StartedAt $nativeStartedAt
    Assert-FingerprintPrecision ((Format-ManagedProcessCanonicalStartTime -Value $nativeStartedAt) -eq
            '2026-08-10T14:44:38.476557Z') `
        'managed process start time was not canonicalized to explicit UTC microsecond precision'
    Assert-FingerprintPrecision (Test-ManagedProcessCanonicalStartTimeEqual -Left $nativeStartedAt -Right $wmiStartedAt) `
        '500 ns variance was not canonicalized to the same WMI-safe microsecond'
    Assert-FingerprintPrecision (-not (Test-ManagedProcessCanonicalStartTimeEqual -Left $nativeStartedAt `
            -Right ([datetimeoffset]::Parse('2026-08-10T14:44:38.4765580Z')))) `
        'a genuine one-microsecond start-time difference was collapsed'
    $nativeOnly = Get-ManagedProcessQueryResult -ProcessId 944 -TimeoutMilliseconds 500 `
        -WmiQuery {param($id,$timeout)New-QueryResult UNAVAILABLE $null 'WMI_QUERY_UNAVAILABLE'} `
        -NativeQuery {param($id)New-QueryResult READY $nativeSnapshot}
    Assert-FingerprintPrecision ($nativeOnly.Outcome -eq 'READY' -and $nativeOnly.Snapshot.QueryKind -eq 'NATIVE_EXACT') `
        'native-only launch publication did not obtain exact process identity'

    $published = New-ManagedProcessOwnershipReceipt -Worktree $worktree -Component SpringBoot -ProcessId 944 `
        -Generation $generation -StateRoot $stateRoot -PassThru -ProcessQuery {param($id)$nativeOnly}
    $receiptPath = Join-Path (Join-Path $stateRoot 'receipts') "$($published.ReceiptId).json"
    $receipt = [IO.File]::ReadAllText($receiptPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-FingerprintPrecision ($receipt.processStartedAt -eq '2026-08-10T14:44:38.476557Z' -and
            $receipt.identityFingerprintVersion -eq 'managed-process-command-v2' -and
            $receipt.startTimeCanonicalPrecision -eq 'UTC_MICROSECOND_TRUNCATED') `
        'ownership receipt did not publish the explicit canonical precision contract'
    $state = [pscustomobject]@{
        springBootPid=944;springBootOwnershipReceiptId=$published.ReceiptId;serviceGeneration=$generation
    }

    $wmiSnapshot = New-FingerprintSnapshot -StartedAt $wmiStartedAt
    $consensus = Get-ManagedProcessQueryResult -ProcessId 944 -TimeoutMilliseconds 500 `
        -WmiQuery {param($id,$timeout)New-QueryResult READY $wmiSnapshot} `
        -NativeQuery {param($id)New-QueryResult READY $nativeSnapshot}
    Assert-FingerprintPrecision ($consensus.Outcome -eq 'READY' -and $consensus.Snapshot.QueryKind -eq 'WMI_NATIVE_CONSENSUS') `
        '500 ns WMI/native start-time precision variance did not produce exact-source consensus'

    $diagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery {param($id)$consensus} -OperationDocuments @()
    Assert-FingerprintPrecision ($diagnosis.Classification -eq 'ORPHAN_EXACT_RECONCILABLE') `
        "500 ns source precision variance produced $($diagnosis.ReasonCode) instead of preserving the exact owner"

    $legacyReceipt = Copy-Receipt $receipt
    $legacyReceipt.operationId = [guid]::NewGuid().ToString()
    $legacyReceipt.processStartedAt = $nativeStartedAt.ToString('o')
    $legacyReceipt.PSObject.Properties.Remove('identityFingerprintVersion')
    $legacyReceipt.PSObject.Properties.Remove('startTimeCanonicalPrecision')
    $legacyReceipt.ownershipIdentityFingerprint = Get-LegacyManagedProcessCommandFingerprint `
        -Snapshot $nativeSnapshot -Worktree $worktree -Component SpringBoot `
        -ReceiptStartedAt $nativeStartedAt -OwnershipReceipt $legacyReceipt -ExactActiveCoordinationProof
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt $legacyReceipt
    $legacyState = [pscustomobject]@{
        springBootPid=944;springBootOwnershipReceiptId=$legacyReceipt.operationId;serviceGeneration=$generation
    }
    $legacyDiagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $legacyState `
        -StateRoot $stateRoot -ProcessQuery {param($id)$consensus} -OperationDocuments @()
    Assert-FingerprintPrecision ($legacyDiagnosis.Classification -eq 'ORPHAN_EXACT_RECONCILABLE') `
        'a typed PR #52 v1 receipt did not receive narrow precision-compatible validation'

    $replayRejected = $false
    try {
        New-ManagedProcessOwnershipReceipt -Worktree $worktree -Component SpringBoot -ProcessId 944 `
            -Generation $generation -StateRoot $stateRoot -ProcessQuery {param($id)$nativeOnly} | Out-Null
    } catch { $replayRejected = $_.Exception.Message -match 'already published' }
    Assert-FingerprintPrecision $replayRejected 'the same canonical process generation replayed a start receipt'

    $differentTimeSnapshot = New-FingerprintSnapshot `
        -StartedAt ([datetimeoffset]::Parse('2026-08-10T14:44:38.4765580Z'))
    $differentTimeResult = New-QueryResult READY $differentTimeSnapshot
    $differentTimeDiagnosis = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $state `
        -StateRoot $stateRoot -ProcessQuery {param($id)$differentTimeResult} -OperationDocuments @()
    Assert-FingerprintPrecision ($differentTimeDiagnosis.Classification -eq 'ORPHAN_UNVERIFIABLE' -and
            $differentTimeDiagnosis.ReasonCode -eq 'PROCESS_START_MISMATCH') `
        'a genuine start-time change did not fail closed as possible PID reuse'

    foreach ($case in @(
        @{Label='PID reuse';State=[pscustomobject]@{springBootPid=945;springBootOwnershipReceiptId=$published.ReceiptId;serviceGeneration=$generation};Snapshot=(New-FingerprintSnapshot -StartedAt $nativeStartedAt -Id 945);Expected='OWNERSHIP_RECEIPT_INVALID'},
        @{Label='wrong executable';State=$state;Snapshot=([pscustomobject]@{ProcessId=944;ParentProcessId=1;Name='powershell.exe';ExecutablePath='C:\other\powershell.exe';CommandLine=$nativeSnapshot.CommandLine;StartedAtUtc=$wmiStartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'});Expected='COMMAND_FINGERPRINT_MISMATCH'},
        @{Label='wrong command';State=$state;Snapshot=([pscustomobject]@{ProcessId=944;ParentProcessId=1;Name='powershell.exe';ExecutablePath=$nativeSnapshot.ExecutablePath;CommandLine='powershell -File C:\other\coordinated-maven-run.ps1 -Application root';StartedAtUtc=$wmiStartedAt.UtcDateTime;QueryKind='FIXTURE_EXACT'});Expected='COMMAND_CONTRACT_MISMATCH'},
        @{Label='wrong generation';State=[pscustomobject]@{springBootPid=944;springBootOwnershipReceiptId=$published.ReceiptId;serviceGeneration='generation-other'};Snapshot=$wmiSnapshot;Expected='GENERATION_MISMATCH'}
    )) {
        $caseResult = New-QueryResult READY $case.Snapshot
        $blocked = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $case.State `
            -StateRoot $stateRoot -ProcessQuery ({param($id)$caseResult}).GetNewClosure() -OperationDocuments @()
        Assert-FingerprintPrecision ($blocked.Classification -eq 'ORPHAN_UNVERIFIABLE' -and
                $blocked.ReasonCode -eq $case.Expected) "$($case.Label) did not fail closed"
    }

    $wrongComponentId = Write-ReceiptVariant $receipt {param($item)$item.component='Ngrok'}
    $wrongWorktreeId = Write-ReceiptVariant $receipt {param($item)$item.worktree='C:\other\worktree'}
    $wrongFingerprintId = Write-ReceiptVariant $receipt {param($item)$item.ownershipIdentityFingerprint=(('0' * 64) -join '')}
    $staleReceiptId = Write-ReceiptVariant $receipt {param($item)$item.status='REVOKED'}
    foreach ($case in @(
        @{Label='wrong component';Id=$wrongComponentId;Expected='OWNERSHIP_RECEIPT_INVALID'},
        @{Label='wrong worktree';Id=$wrongWorktreeId;Expected='OWNERSHIP_RECEIPT_INVALID'},
        @{Label='wrong fingerprint';Id=$wrongFingerprintId;Expected='COMMAND_FINGERPRINT_MISMATCH'},
        @{Label='stale receipt';Id=$staleReceiptId;Expected='OWNERSHIP_RECEIPT_INCOMPLETE'}
    )) {
        $variantState = [pscustomobject]@{
            springBootPid=944;springBootOwnershipReceiptId=$case.Id;serviceGeneration=$generation
        }
        $blocked = Get-ManagedOrphanDiagnosis -Worktree $worktree -Component SpringBoot -State $variantState `
            -StateRoot $stateRoot -ProcessQuery {param($id)$consensus} -OperationDocuments @()
        Assert-FingerprintPrecision ($blocked.Classification -eq 'ORPHAN_UNVERIFIABLE' -and
                $blocked.ReasonCode -eq $case.Expected) "$($case.Label) did not fail closed"
    }

    [pscustomobject]@{status='passed';assertions=$assertions;externalMutations=0} | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

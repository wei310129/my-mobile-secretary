[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\calendar-w11'
$stateRoot = Join-Path $root 'coordination'
$expectedExecutable = 'C:\tools\ngrok.exe'
$expectedArguments = @('http','--url=safe.example.invalid','http://localhost:8080','--log=stdout','--log-level=warn')
$launchObservedAt = [datetimeoffset]::UtcNow
$assertions = 0

function Assert-NgrokStart([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-NgrokSnapshot(
    [int]$Id,
    [AllowNull()][string]$ExecutablePath,
    [AllowNull()][string]$CommandLine,
    [datetimeoffset]$StartedAt,
    [string]$Name = 'ngrok.exe'
) {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name=$Name;ExecutablePath=$ExecutablePath
        CommandLine=$CommandLine;StartedAtUtc=$StartedAt.UtcDateTime
    }
}

function Invoke-NgrokReceipt($SnapshotResults, [int]$ProcessId = 701, [string]$TargetWorktree = $worktree) {
    $queue = [Collections.Queue]::new()
    foreach ($result in @($SnapshotResults)) { $queue.Enqueue($result) }
    $last = @($SnapshotResults)[-1]
    $delays = [Collections.Generic.List[int]]::new()
    $query = {
        param($id)
        if ($queue.Count -gt 0) { return $queue.Dequeue() }
        return $last
    }.GetNewClosure()
    $delay = { param($milliseconds);$delays.Add([int]$milliseconds) }.GetNewClosure()
    $receipt = New-ManagedProcessOwnershipReceipt -Worktree $TargetWorktree -Component Ngrok `
        -ProcessId $ProcessId -StateRoot $stateRoot -ExpectedExecutablePath $expectedExecutable `
        -ExpectedArguments $expectedArguments -ExpectedPort 8080 -LaunchObservedAtUtc $launchObservedAt `
        -ProcessQuery $query -DelayAdapter $delay -SnapshotTimeoutMilliseconds 500 -PassThru
    return [pscustomobject]@{Receipt=$receipt;Delays=$delays}
}

try {
    [IO.Directory]::CreateDirectory($worktree) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    $command = '"C:\tools\ngrok.exe" ' + ($expectedArguments -join ' ')
    $ready = [pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 701 `
        -ExecutablePath $expectedExecutable -CommandLine $command -StartedAt $launchObservedAt.AddMilliseconds(80))}

    $newStart = Invoke-NgrokReceipt @($ready)
    Assert-NgrokStart ($newStart.Receipt.ProcessId -eq 701 -and
            $newStart.Receipt.ReceiptId -match '^[a-f0-9-]{36}$') `
        'an exact newly launched ngrok process did not publish a typed ownership result'
    $receiptPath = Join-Path (Join-Path $stateRoot 'receipts') "$($newStart.Receipt.ReceiptId).json"
    $receiptText = [IO.File]::ReadAllText($receiptPath, [Text.Encoding]::UTF8)
    Assert-NgrokStart ($receiptText -notmatch 'safe\.example' -and $receiptText -notmatch 'CommandLine') `
        'the ownership receipt persisted webhook host or raw process command data'

    $limited = [pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 702 `
        -ExecutablePath $null -CommandLine $null -StartedAt $launchObservedAt.AddMilliseconds(50))}
    $delayedReady = [pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 702 `
        -ExecutablePath $expectedExecutable -CommandLine $command -StartedAt $launchObservedAt.AddMilliseconds(50))}
    $delayed = Invoke-NgrokReceipt @($limited,$delayedReady) -ProcessId 702
    Assert-NgrokStart ($delayed.Receipt.ProcessId -eq 702 -and $delayed.Delays.Count -eq 1) `
        'a temporarily incomplete WMI snapshot was not retried exactly once before publication'

    $neverReadyRejected = $false
    try { Invoke-NgrokReceipt @($limited) -ProcessId 706 | Out-Null } catch { $neverReadyRejected = $true }
    Assert-NgrokStart $neverReadyRejected 'an incomplete snapshot was accepted after the bounded retry window'

    $beforeRejected = @(Get-ChildItem (Join-Path $stateRoot 'receipts') -Filter '*.json' -File).Count
    $wrongCommandRejected = $false
    try {
        Invoke-NgrokReceipt @([pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 703 `
            -ExecutablePath $expectedExecutable -CommandLine '"C:\tools\ngrok.exe" http http://localhost:9999' `
            -StartedAt $launchObservedAt)}) -ProcessId 703 | Out-Null
    } catch { $wrongCommandRejected = $true }
    Assert-NgrokStart $wrongCommandRejected 'a wrong ngrok command or port was accepted'

    $unmanagedRejected = $false
    try {
        Invoke-NgrokReceipt @([pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 704 `
            -ExecutablePath 'C:\other\ngrok.exe' -CommandLine $command -StartedAt $launchObservedAt)}) `
            -ProcessId 704 | Out-Null
    } catch { $unmanagedRejected = $true }
    Assert-NgrokStart $unmanagedRejected 'PID and ngrok.exe name were trusted without the exact executable identity'

    $wrapperRejected = $false
    try {
        Invoke-NgrokReceipt @([pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 707 `
            -Name 'powershell.exe' -ExecutablePath 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' `
            -CommandLine "powershell -Command $command" -StartedAt $launchObservedAt)}) -ProcessId 707 | Out-Null
    } catch { $wrapperRejected = $true }
    Assert-NgrokStart $wrapperRejected 'an uncontracted wrapper was accepted as a trusted ngrok launcher or child identity'

    $wrongPidRejected = $false
    try { Invoke-NgrokReceipt @($ready) -ProcessId 708 | Out-Null } catch { $wrongPidRejected = $true }
    Assert-NgrokStart $wrongPidRejected 'a process query result for another PID was accepted'

    $replayRejected = $false
    try {
        Invoke-NgrokReceipt @([pscustomobject]@{Outcome='READY';Snapshot=(New-NgrokSnapshot -Id 705 `
            -ExecutablePath $expectedExecutable -CommandLine $command -StartedAt $launchObservedAt.AddMinutes(-5))}) `
            -ProcessId 705 | Out-Null
    } catch { $replayRejected = $true }
    Assert-NgrokStart $replayRejected 'a stale process start time was accepted as the new launch'

    $duplicateRejected = $false
    try { Invoke-NgrokReceipt @($ready) | Out-Null } catch { $duplicateRejected = $true }
    Assert-NgrokStart $duplicateRejected 'the same PID and process generation published a second ownership receipt'

    $wrongWorktreeRejected = $false
    try { Invoke-NgrokReceipt @($ready) -TargetWorktree (Join-Path $root 'unregistered') | Out-Null }
    catch { $wrongWorktreeRejected = $true }
    Assert-NgrokStart $wrongWorktreeRejected 'an unregistered worktree was accepted for ngrok ownership'
    $afterRejected = @(Get-ChildItem (Join-Path $stateRoot 'receipts') -Filter '*.json' -File).Count
    Assert-NgrokStart ($afterRejected -eq $beforeRejected) 'a rejected or replayed launch wrote ownership evidence'

    $devStartText = [IO.File]::ReadAllText((Join-Path $scripts 'dev-start.ps1'), [Text.Encoding]::UTF8)
    Assert-NgrokStart ($devStartText -match '(?s)StartNewNgrokPid\s*=.*?New-ManagedProcessOwnershipReceipt.*?-PassThru') `
        'dev-start does not retain the launched root PID before receipt publication'
    Assert-NgrokStart ($devStartText -match '(?s)ngrokLaunchObservedAt\s*=.*?Start-Process.*?New-ManagedProcessOwnershipReceipt.*?-ExpectedExecutablePath\s+\$ngrokExe.*?-ExpectedArguments\s+\$ngrokArguments.*?-ExpectedPort\s+\$AppPort.*?-LaunchObservedAtUtc\s+\$ngrokLaunchObservedAt') `
        'dev-start does not bind receipt publication to the exact executable, command, port and observed launch time'
    Assert-NgrokStart ($devStartText -match '(?s)ngrokPid\s*=\s*\$ngrokOwnership\.ProcessId.*?ngrokOwnershipReceiptId\s*=\s*\$ngrokOwnership\.ReceiptId') `
        'dev-start does not consume the verified process identity returned by receipt publication'
    Assert-NgrokStart ($devStartText -match '(?s)StartNewNgrokPid.*?startup rollback.*?if\s*\(\$devStartFailure\)\s*\{\s*exit 1\s*\}') `
        'receipt failure is not covered by rollback and an explicit nonzero exit boundary'

    [pscustomobject]@{status='passed';assertions=$assertions;liveProcesses='fake';receiptsContainSensitiveCommand=$false} |
        ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

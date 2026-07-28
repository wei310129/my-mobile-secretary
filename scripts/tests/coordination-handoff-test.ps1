[CmdletBinding()]
param([ValidateSet('Disposable')][string]$Adapter = 'Disposable')
$ErrorActionPreference = 'Stop'
$common = Join-Path (Split-Path -Parent $PSScriptRoot) 'coordination-common.ps1'
. $common
$root = Join-Path (Join-Path (Split-Path -Parent $PSScriptRoot) '.handoff-state') ([guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($root) | Out-Null
$stateFile = Join-Path $root 'dev-state.json'
try {
    [IO.File]::WriteAllText($stateFile, '{"springBootPid":123,"serviceGeneration":"fake-generation"}', [Text.UTF8Encoding]::new($false))
    $output = & powershell -ExecutionPolicy Bypass -File (Join-Path (Split-Path -Parent $PSScriptRoot) 'dev-handoff.ps1') -StateRoot $root -DevStateFile $stateFile -SessionId 'fake-session' -AgentId 'fake-agent'
    if ($LASTEXITCODE -ne 0) { throw 'handoff command failed' }
    $receipt = $output | ConvertFrom-Json
    if ($receipt.cleanup -ne 'none; handoff is read-only' -or $receipt.disposition -ne 'kept-persistent') { throw 'handoff receipt cleanup policy is unsafe' }
    $path = Join-Path $root 'receipts\handoff-fake-session.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'handoff receipt was not persisted' }
    $ledger = & powershell -ExecutionPolicy Bypass -File (Join-Path (Split-Path -Parent $PSScriptRoot) 'dev-coordination-ledger.ps1') -StateRoot $root | ConvertFrom-Json
    if ($ledger.receiptCount -ne 1 -or $ledger.dispositions[0].disposition -ne 'kept-persistent') { throw 'ledger did not summarize receipt safely' }
    $first = Reserve-CoordinationFlywayVersion -Application 'fake' -Version 'V999'
    if ($first.Outcome -ne 'READY') { throw 'first Flyway reservation failed' }
    try {
        $job = Start-Job -ScriptBlock { param($path) . $path; (Reserve-CoordinationFlywayVersion -Application 'fake' -Version 'V999').Outcome } -ArgumentList $common
        $job | Wait-Job | Out-Null
        $second = Receive-Job -Job $job -ErrorAction Stop
        Remove-Job -Job $job -Force
        if ($second -ne 'BUSY') { throw 'concurrent Flyway reservation was not blocked' }
    } finally { Exit-CoordinationOperation $first }
    $writer = Enter-CoordinationSourceWriter -Worktree $root
    if ($writer.Outcome -ne 'READY') { throw 'first source writer claim failed' }
    try {
        $job = Start-Job -ScriptBlock { param($path,$worktree) . $path; (Enter-CoordinationSourceWriter -Worktree $worktree).Outcome } -ArgumentList $common,$root
        $job | Wait-Job | Out-Null
        $writerOutcome = Receive-Job -Job $job -ErrorAction Stop
        Remove-Job -Job $job -Force
        if ($writerOutcome -ne 'BUSY') { throw 'second source writer was not blocked' }
    } finally { Exit-CoordinationOperation $writer }
    [pscustomobject]@{status='passed';assertions=8;adapter=$Adapter;livePaths='skipped'} | ConvertTo-Json -Compress
} finally { if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force } }

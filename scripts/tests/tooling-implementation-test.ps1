[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$testRoot = $PSScriptRoot
$tests = @(
    'coordination-doctor-test.ps1',
    'coordination-handoff-test.ps1',
    'coordination-kernel-test.ps1',
    'coordination-lifecycle-test.ps1',
    'coordination-maven-test.ps1',
    'managed-process-lifecycle-test.ps1',
    'managed-process-exact-query-test.ps1',
    'ngrok-managed-lifecycle-start-test.ps1',
    'worktree-secret-resolution-test.ps1',
    'maven-worktree-identity-test.ps1',
    'managed-docker-desktop-test.ps1',
    'service-version-test.ps1',
    'dev-start-managed-receipt-test.ps1',
    'test-strategy-tools-test.ps1',
    'merge-policy-test.ps1',
    'merge-pr-test.ps1',
    'producer-handoff-test.ps1',
    'producer-handoff-workflow-test.ps1',
    'environment-preflight-test.ps1',
    'environment-authority-receipt-test.ps1',
    'environment-managed-operation-review-test.ps1',
    'environment-report-test.ps1',
    'environment-release-review-test.ps1',
    'shared-infrastructure-test.ps1',
    'environment-worktree-review-test.ps1',
    'lifecycle-contract-test.ps1'
)
$results = @()
foreach ($test in $tests) {
    $path = Join-Path $testRoot $test
    $started = Get-Date
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $path 2>$null }
    finally { $ErrorActionPreference = $previousErrorAction }
    $exitCode = [int]$LASTEXITCODE
    $results += [pscustomobject]@{
        test = $test
        exitCode = $exitCode
        durationSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        summary = [string]($output | Select-Object -Last 1)
    }
    if ($exitCode -ne 0) {
        [pscustomobject]@{status='failed';failedTest=$test;results=@($results)} | ConvertTo-Json -Depth 8 -Compress
        exit $exitCode
    }
}
[pscustomobject]@{status='passed';tests=$results.Count;results=@($results)} | ConvertTo-Json -Depth 8 -Compress

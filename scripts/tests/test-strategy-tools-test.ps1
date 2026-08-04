[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$inventory = Join-Path $scriptsRoot 'test-inventory.ps1'
$router = Join-Path $scriptsRoot 'test.ps1'
$ciMaven = Join-Path $scriptsRoot 'mvn-ci.ps1'

function Assert-Tooling {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$summary = & $inventory -Mode Summary -ShardCount 3 -Json | ConvertFrom-Json
Assert-Tooling ($summary.total -eq 384) 'test inventory did not cover all 384 existing test classes'
Assert-Tooling ($summary.automated -eq 381) 'live tests were not precisely excluded from automated inventory'
Assert-Tooling ($summary.live -eq 3) 'live test classification is incorrect'
Assert-Tooling ($summary.migration -eq 8) 'migration test classification is incorrect'
Assert-Tooling ($summary.fast -gt 0 -and $summary.integration -gt 0) 'fast and integration lanes must not be empty'
Assert-Tooling ((@($summary.shards | ForEach-Object integration | Measure-Object -Sum).Sum) -eq $summary.integration) 'integration shards do not cover the full lane'

$fastPlan = @(& $router -Lane Fast -DryRun)
Assert-Tooling (($fastPlan -join "`n") -match '\-Ptest-fast test') 'Fast lane did not use the test-fast profile'
$riskyPlan = @(& $router -Lane Relevant -ChangedPaths 'pom.xml' -DryRun)
Assert-Tooling (($riskyPlan -join "`n") -match 'TEST_ROUTE full') 'pom.xml did not fail closed to Full'
$domainPlan = @(& $router -Lane Relevant -ChangedPaths 'src/main/java/com/aproject/aidriven/mymobilesecretary/booking/domain/Booking.java' -DryRun)
Assert-Tooling (($domainPlan -join "`n") -match 'TEST_LANE fast') 'Relevant lane did not run Fast first'
Assert-Tooling (($domainPlan -join "`n") -match 'TEST_LANE integration') 'Relevant lane did not add related integration tests'

$reportRoot = Join-Path ([IO.Path]::GetTempPath()) ("mms-report-test-" + [guid]::NewGuid().ToString('n'))
try {
    [IO.Directory]::CreateDirectory($reportRoot) | Out-Null
    $automated = @(& $inventory -Mode Classes -Lane all | Where-Object Lane -ne 'live')
    foreach ($testClass in $automated) {
        $report = Join-Path $reportRoot ("TEST-$($testClass.FullyQualifiedName).xml")
        $xml = '<testsuite name="{0}" tests="1" failures="0" errors="0" skipped="0" time="0" />' -f $testClass.FullyQualifiedName
        [IO.File]::WriteAllText($report, $xml, [Text.UTF8Encoding]::new($false))
    }
    $verification = & $inventory -Mode VerifyReports -ReportRoots $reportRoot -Json | ConvertFrom-Json
    Assert-Tooling ($verification.status -eq 'passed') 'report aggregator rejected an exact automated inventory'
    Assert-Tooling ($verification.actual -eq $summary.automated) 'report aggregator class count is incorrect'
} finally {
    $resolvedReportRoot = [IO.Path]::GetFullPath($reportRoot)
    $safeTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedReportRoot.StartsWith($safeTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedReportRoot).StartsWith('mms-report-test-')) {
        Remove-Item -LiteralPath $resolvedReportRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$savedCi = $env:CI
$savedGithubActions = $env:GITHUB_ACTIONS
try {
    $env:CI = 'true'
    $env:GITHUB_ACTIONS = 'true'
    $accepted = & $ciMaven -ValidateOnly test | ConvertFrom-Json
    Assert-Tooling ($accepted.status -eq 'passed') 'CI entrypoint rejected a valid test goal'
    $rejected = $false
    try { & $ciMaven -ValidateOnly clean test | Out-Null } catch { $rejected = $true }
    Assert-Tooling $rejected 'CI entrypoint accepted clean'
} finally {
    $env:CI = $savedCi
    $env:GITHUB_ACTIONS = $savedGithubActions
}

[pscustomobject]@{
    status = 'passed'
    assertions = 13
    total = $summary.total
    automated = $summary.automated
    fast = $summary.fast
    integration = $summary.integration
    live = $summary.live
} | ConvertTo-Json -Compress

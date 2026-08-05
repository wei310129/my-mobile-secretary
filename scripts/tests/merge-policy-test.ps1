[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
$policyScript = Join-Path $scriptsRoot 'merge-policy.ps1'
$policyPath = Join-Path $repoRoot '.github\merge-policy.json'
$sourceHandoffs = Join-Path $repoRoot 'docs\exec-plans\active\handoffs'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("mms-merge-policy-" + [guid]::NewGuid().ToString('n'))
$baseSha = '1111111111111111111111111111111111111111'
$headSha = '2222222222222222222222222222222222222222'
$assertions = 0

function Assert-Policy {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Write-ChangedFiles {
    param([object[]]$Files, [string]$Name)
    $path = Join-Path $tempRoot $Name
    [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject @($Files) -Depth 5), [Text.UTF8Encoding]::new($false))
    return $path
}

function Invoke-ExpectedFailure {
    param([hashtable]$Arguments, [string]$Pattern)
    try {
        & $policyScript @Arguments | Out-Null
        return $false
    } catch {
        $matched = $_.Exception.Message -match $Pattern
        if (-not $matched) { Write-Warning "UNEXPECTED_POLICY_ERROR: $($_.Exception.Message)" }
        return $matched
    }
}

try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    $handoffRoot = Join-Path $tempRoot 'handoffs'
    [IO.Directory]::CreateDirectory($handoffRoot) | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceHandoffs 'laptop-trigger-state.json') -Destination $handoffRoot
    Copy-Item -LiteralPath (Join-Path $sourceHandoffs 'desktop-trigger-state.json') -Destination $handoffRoot

    $laptopPath = Join-Path $handoffRoot 'laptop-trigger-state.json'
    $laptop = Get-Content -LiteralPath $laptopPath -Raw -Encoding utf8 | ConvertFrom-Json
    $laptop.triggers.'TR-SCHEMA-B3-DURABLE-GRANT'.status = 'CONSUMED'
    $laptop.triggers.'TR-SCHEMA-B3-DURABLE-GRANT'.grantStatus = 'CONSUMED'
    [IO.File]::WriteAllText($laptopPath, ($laptop | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))

    $validFiles = Write-ChangedFiles -Name 'valid.json' -Files @(
        @{ filename = '.github/merge-policy.json'; status = 'added' },
        @{ filename = 'scripts/merge-policy.ps1'; status = 'added' }
    )
    $common = @{
        BaseRef = 'main'
        HeadRef = 'tooling/merge-policy-test'
        BaseSha = $baseSha
        HeadSha = $headSha
        ChangedFilesJson = $validFiles
        RepositoryRoot = $repoRoot
        PolicyPath = $policyPath
        HandoffRoot = $handoffRoot
        SkipAncestryValidation = $true
        Json = $true
    }
    $valid = & $policyScript @common | ConvertFrom-Json
    Assert-Policy ($valid.status -eq 'passed') 'valid tooling PR was rejected'
    Assert-Policy ($valid.branchPolicy -eq 'tooling') 'wrong branch policy selected'
    Assert-Policy ($valid.requiresSerial -eq $true) 'policy changes did not require serial regression'

    $unknown = $common.Clone()
    $unknown.HeadRef = 'feature/unowned'
    Assert-Policy (Invoke-ExpectedFailure -Arguments $unknown -Pattern 'exactly one branch policy') 'unknown branch did not fail closed'

    $javaFiles = Write-ChangedFiles -Name 'java.json' -Files @(
        @{ filename = 'src/main/java/com/aproject/aidriven/mymobilesecretary/booking/domain/Booking.java'; status = 'modified' }
    )
    $toolingJava = $common.Clone()
    $toolingJava.ChangedFilesJson = $javaFiles
    Assert-Policy (Invoke-ExpectedFailure -Arguments $toolingJava -Pattern 'does not allow') 'tooling branch could modify Java'

    $laptopStateFiles = Write-ChangedFiles -Name 'laptop-state.json' -Files @(
        @{ filename = 'docs/exec-plans/active/handoffs/laptop-trigger-state.json'; status = 'modified' }
    )
    $desktopOwnsLaptop = $common.Clone()
    $desktopOwnsLaptop.HeadRef = 'desktop/booking-b3-core-state'
    $desktopOwnsLaptop.ChangedFilesJson = $laptopStateFiles
    Assert-Policy (Invoke-ExpectedFailure -Arguments $desktopOwnsLaptop -Pattern 'does not allow|forbids') 'desktop branch could modify laptop state'

    $migrationFiles = Write-ChangedFiles -Name 'migration.json' -Files @(
        @{ filename = 'src/main/resources/db/migration/V93__existing.sql'; status = 'modified' }
    )
    $modifiedMigration = $common.Clone()
    $modifiedMigration.HeadRef = 'integration/migration-test'
    $modifiedMigration.ChangedFilesJson = $migrationFiles
    Assert-Policy (Invoke-ExpectedFailure -Arguments $modifiedMigration -Pattern 'immutable') 'existing migration modification was accepted'

    $removedWorkflowFiles = Write-ChangedFiles -Name 'removed.json' -Files @(
        @{ filename = '.github/workflows/test-gates.yml'; status = 'removed' }
    )
    $removedWorkflow = $common.Clone()
    $removedWorkflow.ChangedFilesJson = $removedWorkflowFiles
    Assert-Policy (Invoke-ExpectedFailure -Arguments $removedWorkflow -Pattern 'cannot be removed or renamed') 'sensitive workflow deletion was accepted'

    $brokenLaptop = Get-Content -LiteralPath $laptopPath -Raw -Encoding utf8 | ConvertFrom-Json
    $brokenLaptop.triggers.'TR-SCHEMA-B3-DURABLE-GRANT'.status = 'READY'
    $brokenLaptop.triggers.'TR-SCHEMA-B3-DURABLE-GRANT'.grantStatus = 'GRANTED_ONCE'
    [IO.File]::WriteAllText($laptopPath, ($brokenLaptop | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    Assert-Policy (Invoke-ExpectedFailure -Arguments $common -Pattern 'must be CONSUMED') 'consumed one-time trigger inconsistency was accepted'

    [pscustomobject]@{ status = 'passed'; assertions = $assertions } | ConvertTo-Json -Compress
} finally {
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolved).StartsWith('mms-merge-policy-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}

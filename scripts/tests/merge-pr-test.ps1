[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
$mergeScript = Join-Path $scriptsRoot 'merge-pr.ps1'
$policyPath = Join-Path $repoRoot '.github\merge-policy.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("mms-merge-pr-" + [guid]::NewGuid().ToString('n'))
$headSha = '2222222222222222222222222222222222222222'
$assertions = 0

function Assert-Merge {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Write-Fixture {
    param([string]$Name, [object]$Value)
    $path = Join-Path $tempRoot $Name
    [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $Value -Depth 12), [Text.UTF8Encoding]::new($false))
    return $path
}

function Expected-Failure {
    param([hashtable]$Arguments, [string]$Pattern)
    try {
        & $mergeScript @Arguments | Out-Null
        return $false
    } catch {
        return $_.Exception.Message -match $Pattern
    }
}

try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    $checks = @('Merge policy', 'Fast tests', 'Integration shard 0', 'Integration shard 1', 'Integration shard 2', 'Automated regression complete') |
        ForEach-Object { @{ name = $_; status = 'COMPLETED'; conclusion = 'SUCCESS' } }
    $pr = @{
        number = 42
        url = 'https://example.invalid/pr/42'
        isDraft = $false
        state = 'OPEN'
        headRefName = 'tooling/merge-policy-test'
        headRefOid = $headSha
        baseRefName = 'main'
        mergeable = 'MERGEABLE'
        mergeStateStatus = 'CLEAN'
        statusCheckRollup = @($checks)
    }
    $protectedContexts = @('Merge policy', 'Fast tests', 'Integration shard 0', 'Integration shard 1', 'Integration shard 2', 'Automated regression complete')
    $protection = @{
        required_status_checks = @{
            strict = $true
            contexts = @($protectedContexts)
            checks = @($protectedContexts | ForEach-Object { @{ context = $_; app_id = 15368 } })
        }
        required_pull_request_reviews = @{ required_approving_review_count = 0 }
        enforce_admins = @{ enabled = $true }
        allow_force_pushes = @{ enabled = $false }
        allow_deletions = @{ enabled = $false }
        required_conversation_resolution = @{ enabled = $true }
    }
    $prPath = Write-Fixture -Name 'pr.json' -Value $pr
    $protectionPath = Write-Fixture -Name 'protection.json' -Value $protection
    $common = @{
        PrNumber = 42
        ExpectedHeadSha = $headSha
        UserAuthorizedThisTurn = $true
        PolicyPath = $policyPath
        PrJsonPath = $prPath
        ProtectionJsonPath = $protectionPath
        Json = $true
    }
    $valid = & $mergeScript @common | ConvertFrom-Json
    Assert-Merge ($valid.status -eq 'passed' -and $valid.adminBypass -eq $false) 'valid authorized merge preflight was rejected'

    $wrongHead = $common.Clone()
    $wrongHead.ExpectedHeadSha = '3333333333333333333333333333333333333333'
    Assert-Merge (Expected-Failure -Arguments $wrongHead -Pattern 'obtain new authorization') 'changed head did not invalidate authorization'

    $noAuthorization = $common.Clone()
    $noAuthorization.UserAuthorizedThisTurn = $false
    Assert-Merge (Expected-Failure -Arguments $noAuthorization -Pattern 'Current-turn user authorization') 'missing current-turn authorization was accepted'

    $draftPr = $pr.Clone()
    $draftPr.isDraft = $true
    $draftPath = Write-Fixture -Name 'draft-pr.json' -Value $draftPr
    $draft = $common.Clone()
    $draft.PrJsonPath = $draftPath
    Assert-Merge (Expected-Failure -Arguments $draft -Pattern 'still draft') 'draft PR was accepted'

    $missingCheckPr = $pr.Clone()
    $missingCheckPr.statusCheckRollup = @($checks | Where-Object name -ne 'Merge policy')
    $missingCheckPath = Write-Fixture -Name 'missing-check-pr.json' -Value $missingCheckPr
    $missingCheck = $common.Clone()
    $missingCheck.PrJsonPath = $missingCheckPath
    Assert-Merge (Expected-Failure -Arguments $missingCheck -Pattern "Required check 'Merge policy'") 'missing Merge policy check was accepted'

    $bypassProtection = $protection.Clone()
    $bypassProtection.enforce_admins = @{ enabled = $false }
    $bypassPath = Write-Fixture -Name 'bypass-protection.json' -Value $bypassProtection
    $bypass = $common.Clone()
    $bypass.ProtectionJsonPath = $bypassPath
    Assert-Merge (Expected-Failure -Arguments $bypass -Pattern 'apply to administrators') 'administrator bypass was accepted'

    [pscustomobject]@{ status = 'passed'; assertions = $assertions } | ConvertTo-Json -Compress
} finally {
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolved).StartsWith('mms-merge-pr-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}

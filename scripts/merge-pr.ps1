[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(1, 2147483647)][int]$PrNumber,
    [Parameter(Mandatory)][string]$ExpectedHeadSha,
    [Parameter(Mandatory)][switch]$UserAuthorizedThisTurn,
    [string]$Repository = 'wei310129/my-mobile-secretary',
    [string]$PolicyPath,
    [string]$PrJsonPath,
    [string]$ProtectionJsonPath,
    [switch]$Execute,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'

if (-not $PolicyPath) {
    $PolicyPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.github\merge-policy.json'
}

function Fail-Merge {
    param([Parameter(Mandatory)][string]$Message)
    throw "MERGE_PREFLIGHT_FAILED: $Message"
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail-Merge "JSON file not found: $Path" }
    return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json
}

if (-not $UserAuthorizedThisTurn) {
    Fail-Merge 'Current-turn user authorization is required and cannot be inferred from prior turns, CI, or PR state.'
}
if ($ExpectedHeadSha -notmatch '^[0-9a-f]{40}$') { Fail-Merge 'ExpectedHeadSha must be a canonical 40-character SHA.' }
if (-not (Test-Path -LiteralPath $PolicyPath -PathType Leaf)) { Fail-Merge "Policy file not found: $PolicyPath" }
$policy = Read-JsonFile -Path $PolicyPath

if ($PrJsonPath) {
    $pr = Read-JsonFile -Path $PrJsonPath
} else {
    $prText = & gh pr view $PrNumber --repo $Repository --json number,url,isDraft,state,headRefName,headRefOid,baseRefName,mergeable,mergeStateStatus,statusCheckRollup 2>&1
    if ($LASTEXITCODE -ne 0) { Fail-Merge "Unable to read PR #${PrNumber}: $($prText -join ' ')" }
    $pr = $prText | ConvertFrom-Json
}

if ([int]$pr.number -ne $PrNumber) { Fail-Merge 'PR response number does not match the authorized PR.' }
if ($pr.state -ne 'OPEN') { Fail-Merge "PR #$PrNumber is not OPEN." }
if ($pr.isDraft) { Fail-Merge "PR #$PrNumber is still draft." }
if ($pr.baseRefName -ne $policy.protectedBase) { Fail-Merge "PR base must be '$($policy.protectedBase)'." }
if ($pr.headRefOid -ne $ExpectedHeadSha) {
    Fail-Merge "PR head changed from authorized SHA '$ExpectedHeadSha' to '$($pr.headRefOid)'; obtain new authorization."
}
if ($pr.mergeable -ne 'MERGEABLE' -or $pr.mergeStateStatus -ne 'CLEAN') {
    Fail-Merge "PR is not cleanly mergeable (mergeable=$($pr.mergeable), state=$($pr.mergeStateStatus))."
}

$successfulChecks = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($check in @($pr.statusCheckRollup)) {
    $name = if ($check.name) { [string]$check.name } else { [string]$check.context }
    $conclusion = if ($check.conclusion) { [string]$check.conclusion } else { [string]$check.state }
    $status = [string]$check.status
    if ($status -and $status -ne 'COMPLETED') { continue }
    if ($conclusion -in @('SUCCESS', 'success')) { [void]$successfulChecks.Add($name) }
}
foreach ($requiredCheck in @($policy.requiredChecks)) {
    if (-not $successfulChecks.Contains([string]$requiredCheck)) {
        Fail-Merge "Required check '$requiredCheck' is missing or not successful on authorized head SHA."
    }
}

if ($ProtectionJsonPath) {
    $protection = Read-JsonFile -Path $ProtectionJsonPath
} else {
    $protectionText = & gh api "repos/$Repository/branches/$($policy.protectedBase)/protection" 2>&1
    if ($LASTEXITCODE -ne 0) { Fail-Merge "Unable to read branch protection: $($protectionText -join ' ')" }
    $protection = $protectionText | ConvertFrom-Json
}

if (-not $protection.required_status_checks.strict) { Fail-Merge 'Branch protection must require branches to be up to date.' }
if ($null -eq $protection.required_pull_request_reviews) { Fail-Merge 'Branch protection must require a pull request.' }
if (-not $protection.enforce_admins.enabled) { Fail-Merge 'Branch protection must apply to administrators; bypass is forbidden.' }
if ($protection.allow_force_pushes.enabled) { Fail-Merge 'Force pushes must remain disabled.' }
if ($protection.allow_deletions.enabled) { Fail-Merge 'Protected branch deletion must remain disabled.' }
if (-not $protection.required_conversation_resolution.enabled) { Fail-Merge 'Review conversations must be resolved before merge.' }
$protectedChecks = @($protection.required_status_checks.contexts)
foreach ($requiredCheck in @($policy.requiredChecks)) {
    if ($requiredCheck -notin $protectedChecks) {
        Fail-Merge "Branch protection does not require '$requiredCheck'."
    }
    $boundCheck = @($protection.required_status_checks.checks | Where-Object context -eq $requiredCheck)
    if ($boundCheck.Count -ne 1 -or [int64]$boundCheck[0].app_id -ne [int64]$policy.requiredCheckAppId) {
        Fail-Merge "Required check '$requiredCheck' is not bound to the approved GitHub Actions app."
    }
}

$result = [ordered]@{
    status = 'passed'
    repository = $Repository
    pr = $PrNumber
    url = [string]$pr.url
    headSha = $ExpectedHeadSha
    base = [string]$pr.baseRefName
    requiredChecks = @($policy.requiredChecks).Count
    protectionStrict = $true
    adminBypass = $false
    merged = $false
    mergeCommit = $null
}

if ($Execute) {
    if ($PrJsonPath -or $ProtectionJsonPath) { Fail-Merge 'Fixture JSON cannot be used with -Execute.' }
    $mergeOutput = & gh pr merge $PrNumber --repo $Repository --merge 2>&1
    if ($LASTEXITCODE -ne 0) { Fail-Merge "GitHub rejected merge: $($mergeOutput -join ' ')" }
    $mergedText = & gh pr view $PrNumber --repo $Repository --json state,headRefOid,mergeCommit 2>&1
    if ($LASTEXITCODE -ne 0) { Fail-Merge 'Merge completed but post-merge verification could not read the PR.' }
    $mergedPr = $mergedText | ConvertFrom-Json
    if ($mergedPr.state -ne 'MERGED' -or $mergedPr.headRefOid -ne $ExpectedHeadSha -or -not $mergedPr.mergeCommit.oid) {
        Fail-Merge 'Post-merge PR state does not match the authorized head SHA.'
    }
    $result.merged = $true
    $result.mergeCommit = [string]$mergedPr.mergeCommit.oid
}

if ($Json) { $result | ConvertTo-Json -Depth 5 -Compress } else { [pscustomobject]$result | Format-List }

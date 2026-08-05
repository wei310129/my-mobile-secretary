[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')

function Assert-ReviewTest {
    param([bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-ReviewCommand {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsRoot 'dev-environment-review.ps1') @Arguments 2>$null }
    finally { $ErrorActionPreference = $previousErrorAction }
    [pscustomobject]@{ExitCode=[int]$LASTEXITCODE;Output=($output -join "`n")}
}

function Invoke-AssertCommand {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsRoot 'assert-dev-environment-review.ps1') @Arguments 2>$null }
    finally { $ErrorActionPreference = $previousErrorAction }
    [pscustomobject]@{ExitCode=[int]$LASTEXITCODE;Output=($output -join "`n")}
}

$testId = [guid]::NewGuid().ToString('n')
$stateRoot = Join-Path $scriptsRoot ".environment-worktree-test-state\$testId"
$approvedRoot = Join-Path $repoRoot 'docs\exec-plans\evidence\development-environment'
$customEvidenceRoot = Join-Path $approvedRoot "worktree-test-$testId"
$evidenceName = 'automatic.json'
$evidencePath = Join-Path $customEvidenceRoot $evidenceName
$gate = "worktree-$testId"
$unregisteredRoot = Join-Path $repoRoot "unregistered-review-$testId"
$crossRepoRoot = Join-Path ([IO.Path]::GetTempPath()) "environment-cross-repo-$testId"
$assertions = 0

try {
    [IO.Directory]::CreateDirectory($unregisteredRoot) | Out-Null
    [IO.Directory]::CreateDirectory($crossRepoRoot) | Out-Null
    $relativeEvidence = "docs\exec-plans\evidence\development-environment\worktree-test-$testId\$evidenceName"
    $reviewResult = Invoke-ReviewCommand -Arguments @(
        '-ReleaseGate',$gate,'-Mode','Automatic','-Capability','READ_ONLY',
        '-TargetWorktree',$repoRoot,'-EvidenceRoot',$customEvidenceRoot,'-EvidencePath',$evidenceName,
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json'
    )
    $assertions++
    Assert-ReviewTest ($reviewResult.ExitCode -eq 0) "verified worktree Automatic review failed: $($reviewResult.Output)"
    $review = $reviewResult.Output | ConvertFrom-Json
    $identity = Assert-EnvironmentTargetWorktree -TargetWorktree $repoRoot -AnchorWorktree $repoRoot
    $assertions++
    Assert-ReviewTest ($review.outcome -eq 'PASS' -and $review.openCount -eq 0 -and
        $review.targetWorktree.worktreeId -eq $identity.WorktreeId -and
        [string]::Equals([string]$review.targetWorktree.path,$identity.Path,[StringComparison]::OrdinalIgnoreCase)) 'Automatic review did not record the verified target worktree identity'
    $assertions++
    Assert-ReviewTest ([string]::Equals([IO.Path]::GetFullPath($review.evidencePath),[IO.Path]::GetFullPath($evidencePath),[StringComparison]::OrdinalIgnoreCase)) 'Automatic review wrote an unexpected evidence path'
    $assertions++
    Assert-ReviewTest (Test-Path -LiteralPath $evidencePath -PathType Leaf) 'Automatic review evidence was not written to the target worktree'

    $validAssert = Invoke-AssertCommand -Arguments @(
        '-ReleaseGate',$gate,'-Mode','Automatic','-MachineAlias','test-laptop','-TargetWorktree',$repoRoot,
        '-EvidenceRoot',$customEvidenceRoot,'-EvidencePath',$evidenceName,'-StateRoot',$stateRoot,
        '-RequiredCapability','READ_ONLY'
    )
    $assertions++
    Assert-ReviewTest ($validAssert.ExitCode -eq 0) "valid target worktree evidence was rejected: $($validAssert.Output)"

    $escapeRoot = Join-Path $repoRoot 'docs'
    $escapeRootResult = Invoke-ReviewCommand -Arguments @(
        '-ReleaseGate',"$gate-escape-root",'-Mode','Automatic','-Capability','READ_ONLY',
        '-TargetWorktree',$repoRoot,'-EvidenceRoot',$escapeRoot,'-StateRoot',$stateRoot,'-MachineAlias','test-laptop'
    )
    $assertions++
    Assert-ReviewTest ($escapeRootResult.ExitCode -ne 0) 'evidence root path escape was accepted'

    $escapePathResult = Invoke-ReviewCommand -Arguments @(
        '-ReleaseGate',"$gate-escape-path",'-Mode','Automatic','-Capability','READ_ONLY',
        '-TargetWorktree',$repoRoot,'-EvidenceRoot',$customEvidenceRoot,'-EvidencePath','..\..\escape.json',
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop'
    )
    $assertions++
    Assert-ReviewTest ($escapePathResult.ExitCode -ne 0) 'evidence path traversal was accepted'

    $unregisteredResult = Invoke-ReviewCommand -Arguments @(
        '-ReleaseGate',"$gate-unregistered",'-Mode','Automatic','-Capability','READ_ONLY',
        '-TargetWorktree',$unregisteredRoot,'-StateRoot',$stateRoot,'-MachineAlias','test-laptop'
    )
    $assertions++
    Assert-ReviewTest ($unregisteredResult.ExitCode -ne 0) 'unregistered worktree was accepted'

    $crossRepoResult = Invoke-ReviewCommand -Arguments @(
        '-ReleaseGate',"$gate-cross-repo",'-Mode','Automatic','-Capability','READ_ONLY',
        '-TargetWorktree',$crossRepoRoot,'-StateRoot',$stateRoot,'-MachineAlias','test-laptop'
    )
    $assertions++
    Assert-ReviewTest ($crossRepoResult.ExitCode -ne 0) 'cross-repository target was accepted'

    $aliasResult = Invoke-AssertCommand -Arguments @(
        '-ReleaseGate',$gate,'-Mode','Automatic','-MachineAlias','test-desktop','-TargetWorktree',$repoRoot,
        '-EvidenceRoot',$customEvidenceRoot,'-EvidencePath',$evidenceName,'-StateRoot',$stateRoot
    )
    $assertions++
    Assert-ReviewTest ($aliasResult.ExitCode -ne 0) 'machine alias mismatch was accepted'

    $primaryRoot = (Get-EnvironmentGitLayout -RepoRoot $repoRoot).PrimaryRoot
    $rootEvidenceResult = Invoke-AssertCommand -Arguments @(
        '-ReleaseGate',$gate,'-Mode','Automatic','-MachineAlias','test-laptop','-TargetWorktree',$primaryRoot,
        '-EvidencePath',$evidencePath,'-StateRoot',$stateRoot
    )
    $assertions++
    Assert-ReviewTest ($rootEvidenceResult.ExitCode -ne 0) 'root evidence was accepted as consumer worktree evidence'

    $staleReview = $review | ConvertTo-Json -Depth 12 | ConvertFrom-Json
    $staleReview.targetWorktree.worktreeId = ('0' * 64)
    $staleRejected = $false
    try {
        Assert-EnvironmentReviewDocument -Review $staleReview -ReleaseGate $gate -Mode Automatic `
            -RepoRoot $repoRoot -TargetWorktree $repoRoot -AnchorWorktree $repoRoot -EvidenceRoot $customEvidenceRoot `
            -MachineAlias test-laptop -RequiredCapability READ_ONLY | Out-Null
    } catch { $staleRejected = $true }
    $assertions++
    Assert-ReviewTest $staleRejected 'review with a mismatched target worktree identity was accepted'

    [pscustomobject]@{status='passed';assertions=$assertions;targetWorktree=$identity.Path;evidencePath=$evidencePath} | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $evidencePath -PathType Leaf) { Remove-Item -LiteralPath $evidencePath -Force }
    if (Test-Path -LiteralPath $customEvidenceRoot -PathType Container) { Remove-Item -LiteralPath $customEvidenceRoot -Recurse -Force }
    if (Test-Path -LiteralPath $stateRoot -PathType Container) { Remove-Item -LiteralPath $stateRoot -Recurse -Force }
    if (Test-Path -LiteralPath $unregisteredRoot -PathType Container) { Remove-Item -LiteralPath $unregisteredRoot -Recurse -Force }
    if (Test-Path -LiteralPath $crossRepoRoot -PathType Container) { Remove-Item -LiteralPath $crossRepoRoot -Recurse -Force }
}

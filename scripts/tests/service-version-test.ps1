$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\_devops-common.ps1"
$assertions=0

function Assert-Version {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-VersionFixture {
    param(
        [string]$Sha = '20de79679f41f3144b8a56afe7d462fa10be01a7',
        [long]$BuildNumber = 199,
        [bool]$Dirty = $false,
        [bool]$Available = $true,
        [string]$ContentFingerprint = 'content-a',
        [string]$WorktreeId = 'worktree-a'
    )
    return [pscustomobject]@{
        Available = $Available
        GitSha = $Sha
        BuildNumber = $BuildNumber
        Dirty = $Dirty
        ProductionContentFingerprint = $ContentFingerprint
        WorktreeId = $WorktreeId
    }
}

$current = Compare-ServiceVersion -Running (New-VersionFixture) -Checkout (New-VersionFixture)
Assert-Version ($current.Status -eq 'CURRENT') 'matching clean versions must be CURRENT'

$stale = Compare-ServiceVersion `
    -Running (New-VersionFixture -Sha '1111111111111111111111111111111111111111') `
    -Checkout (New-VersionFixture)
Assert-Version ($stale.Status -eq 'STALE') 'different SHA must be STALE'

$dirty = Compare-ServiceVersion -Running (New-VersionFixture -Dirty $true) -Checkout (New-VersionFixture -Dirty $true)
Assert-Version ($dirty.Status -eq 'VERIFIED_DIRTY') 'matching dirty production content must be VERIFIED_DIRTY'

$dirtyStale = Compare-ServiceVersion `
    -Running (New-VersionFixture -Dirty $true -ContentFingerprint 'built-content') `
    -Checkout (New-VersionFixture -Dirty $true -ContentFingerprint 'changed-content')
Assert-Version ($dirtyStale.Status -eq 'STALE') 'production changes after build must be STALE'

$wrongWorktree = Compare-ServiceVersion `
    -Running (New-VersionFixture -WorktreeId 'linked-a') `
    -Checkout (New-VersionFixture -WorktreeId 'linked-b')
Assert-Version ($wrongWorktree.Status -eq 'STALE') 'runtime identity from another worktree must be STALE'

$unknown = Compare-ServiceVersion `
    -Running (New-VersionFixture -Available $false) `
    -Checkout (New-VersionFixture)
Assert-Version ($unknown.Status -eq 'UNKNOWN') 'missing metadata must be UNKNOWN'

$checkout = Get-CheckoutServiceVersion
Assert-Version $checkout.Available 'current repository checkout metadata must be readable'
Assert-Version ($checkout.BuildNumber -gt 0) 'checkout build number must be positive'
Assert-Version ($checkout.GitSha -match '^[0-9a-f]{40}$') 'checkout SHA must be canonical'
$generationIdentity=[pscustomobject]@{serviceGeneration='generation-a'}
Assert-Version (Test-RuntimeContentGeneration -Identity $generationIdentity -ServiceGeneration 'generation-a') 'matching runtime generation was rejected'
Assert-Version (-not (Test-RuntimeContentGeneration -Identity $generationIdentity -ServiceGeneration 'generation-b')) 'stale runtime generation was accepted'
$probeCalls=0
$skippedProbe=Invoke-DevExternalLineProbe -Requested $false -Skipped $false -NoNgrokRequired $false -Probe {$script:probeCalls++;[pscustomobject]@{Success=$false}}
Assert-Version (-not $skippedProbe.Executed -and $probeCalls -eq 0) 'external LINE probe ran without explicit request'
$failedProbe=Invoke-DevExternalLineProbe -Requested $true -Skipped $false -NoNgrokRequired $false -Probe {$script:probeCalls++;[pscustomobject]@{Success=$false}}
Assert-Version ($failedProbe.Executed -and -not $failedProbe.Success) 'requested failing LINE probe did not fail closed'
Assert-Version ((Get-DevStatusExitCode -AllHealthy $false) -eq 1) 'unhealthy dev-status exit contract was not nonzero'
Assert-Version ((Get-DevStatusExitCode -AllHealthy $true) -eq 0) 'healthy dev-status exit contract was not zero'

$fixtureRoot = Join-Path $RepoRoot ("var\script-test-temp\mms-svc-fingerprint-" + [guid]::NewGuid().ToString('n').Substring(0,8))
try {
    [IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'src\main\java')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'docs')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'src\main\java\App.java'),'v1',[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'docs\note.md'),'docs-v1',[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'pom.xml'),'<project/>',[Text.UTF8Encoding]::new($false))
    & git -C $fixtureRoot init -q
    & git -C $fixtureRoot config user.email tooling-test@example.invalid
    & git -C $fixtureRoot config user.name tooling-test
    & git -C $fixtureRoot add src/main/java/App.java docs/note.md pom.xml
    & git -C $fixtureRoot commit -q -m baseline
    if ($LASTEXITCODE -ne 0) { throw 'temporary Git fixture could not be committed' }
    $cleanFingerprint = Get-ProductionContentIdentity -RepoRoot $fixtureRoot
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'docs\note.md'),'docs-v2',[Text.UTF8Encoding]::new($false))
    $docsFingerprint = Get-ProductionContentIdentity -RepoRoot $fixtureRoot
    Assert-Version ($docsFingerprint.Fingerprint -eq $cleanFingerprint.Fingerprint) 'docs-only changes altered production content identity'
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'src\main\java\App.java'),'v2',[Text.UTF8Encoding]::new($false))
    $sourceFingerprint = Get-ProductionContentIdentity -RepoRoot $fixtureRoot
    Assert-Version ($sourceFingerprint.Fingerprint -ne $cleanFingerprint.Fingerprint) 'production source changes did not alter content identity'
    $linkedFingerprint = Get-ProductionContentIdentity -RepoRoot $RepoRoot
    $linkedGitPointer = [IO.File]::ReadAllText((Join-Path $RepoRoot '.git'),[Text.Encoding]::UTF8).Trim()
    $linkedGitDirectory = [IO.Path]::GetFullPath(($linkedGitPointer -replace '^gitdir:\s*',''))
    $linkedCommonPointer = [IO.File]::ReadAllText((Join-Path $linkedGitDirectory 'commondir'),[Text.Encoding]::UTF8).Trim()
    $primaryRoot = Split-Path -Parent ([IO.Path]::GetFullPath((Join-Path $linkedGitDirectory $linkedCommonPointer)))
    $primaryFingerprint = Get-ProductionContentIdentity -RepoRoot $primaryRoot
    Assert-Version ($linkedFingerprint.WorktreeId -ne $primaryFingerprint.WorktreeId) 'registered linked worktree did not receive its own identity'
    Assert-Version ($linkedFingerprint.Head -eq ((& git -C $RepoRoot rev-parse HEAD | Select-Object -First 1).Trim())) 'linked worktree did not use its own HEAD'
    Assert-Version ($linkedFingerprint.GitDirectoryId -ne $primaryFingerprint.GitDirectoryId) 'linked worktree did not use its own Git directory'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}

[pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress

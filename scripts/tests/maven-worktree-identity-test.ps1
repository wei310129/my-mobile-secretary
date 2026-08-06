[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) '_maven-quiet.ps1')

$assertions = 0
function Assert-MavenWorktreeTest {
    param([bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$rawGitDirectory = @(& git -C $repoRoot rev-parse --git-dir 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace([string]($rawGitDirectory | Select-Object -First 1))) {
    throw 'test worktree Git metadata could not be resolved'
}
$gitDirectory = [IO.Path]::GetFullPath([string]($rawGitDirectory | Select-Object -First 1)).TrimEnd('\', '/')
$expectedAnchor = [IO.Path]::GetFullPath((Join-Path $gitDirectory 'logs')).TrimEnd('\', '/')
$anchor = Resolve-MavenGitDirectoryAnchor -RepoRoot $repoRoot
Assert-MavenWorktreeTest ([string]::Equals($anchor, $expectedAnchor, [StringComparison]::OrdinalIgnoreCase)) `
    'Maven Git anchor did not stay on the registered worktree metadata'
Assert-MavenWorktreeTest (Test-Path -LiteralPath $anchor -PathType Container) 'Maven Git anchor directory is not present'

$seen = Invoke-WithMavenWorktreeGitContext -RepoRoot $repoRoot -Runner {
    [pscustomobject]@{
        MavenGitDirectory = [Environment]::GetEnvironmentVariable('MMS_MAVEN_GIT_DIRECTORY', 'Process')
        GitDirectory = [Environment]::GetEnvironmentVariable('GIT_DIR', 'Process')
        GitWorkTree = [Environment]::GetEnvironmentVariable('GIT_WORK_TREE', 'Process')
    }
}
Assert-MavenWorktreeTest ([string]::Equals([string]$seen.MavenGitDirectory, $anchor, [StringComparison]::OrdinalIgnoreCase)) `
    'Maven runner did not receive the verified worktree Git anchor'
$expectedGitDirectory = [IO.Path]::GetFullPath($gitDirectory).TrimEnd('\', '/')
Assert-MavenWorktreeTest ([string]::Equals([string]$seen.GitDirectory, $expectedGitDirectory, [StringComparison]::OrdinalIgnoreCase)) `
    'Maven runner did not receive the verified GIT_DIR'
Assert-MavenWorktreeTest ([string]::Equals([string]$seen.GitWorkTree, $repoRoot, [StringComparison]::OrdinalIgnoreCase)) `
    'Maven runner did not receive the target GIT_WORK_TREE'
Assert-MavenWorktreeTest ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('MMS_MAVEN_GIT_DIRECTORY', 'Process')) -and
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('GIT_DIR', 'Process')) -and
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('GIT_WORK_TREE', 'Process'))) `
    'Maven worktree Git context was not restored after the runner'

$pom = Get-Content -Raw -Encoding UTF8 (Join-Path $repoRoot 'pom.xml')
Assert-MavenWorktreeTest ($pom -match '<dotGitDirectory>\$\{env\.MMS_MAVEN_GIT_DIRECTORY\}</dotGitDirectory>') `
    'POM does not bind Git metadata to the verified Maven worktree context'
Assert-MavenWorktreeTest ($pom -match '<useNativeGit>true</useNativeGit>') 'POM does not enable native Git metadata extraction'

[pscustomobject]@{ status = 'passed'; assertions = $assertions; anchor = $anchor; resources = 'unchanged' } |
    ConvertTo-Json -Compress

[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('root', 'dispatcher')][string]$Application,
    [string]$Profile = 'local'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\coordination-maven.ps1"
. "$PSScriptRoot\_maven-quiet.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    Write-Error "找不到 Maven wrapper：$mavenWrapper"
    exit 1
}

$worktree = if ($Application -eq 'root') { $repoRoot } else { Join-Path $repoRoot 'internal\ai-dispatcher' }
$mavenArguments = if ($Application -eq 'root') {
    @('spring-boot:run', '-Dmaven.test.skip=true', "-Dspring-boot.run.profiles=$Profile")
} else {
    @('-f', 'internal\ai-dispatcher\pom.xml', 'spring-boot:run')
}

try {
    $coordination = Invoke-WithMavenWorktreeGitContext -RepoRoot $repoRoot -Runner {
        Invoke-CoordinatedMavenOperation -Application $Application -Worktree $worktree -Operation SpringBootRun -Runner {
            Push-Location $repoRoot
            try {
                & $mavenWrapper @mavenArguments 2>&1 | ForEach-Object { Write-Host $_ }
                return $LASTEXITCODE
            } finally { Pop-Location }
        }
    }
    if ($coordination.Outcome -eq 'BUSY') {
        Write-Error 'Maven runtime target is busy; start was not attempted.'
        exit 1
    }
    exit [int]$coordination.ExitCode
} catch {
    Write-Error "Coordinated Maven runtime failed: $($_.Exception.Message)"
    exit 1
}

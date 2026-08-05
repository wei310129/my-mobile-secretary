Set-StrictMode -Version Latest

function Assert-RepositoryMavenInvocation {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $repository = [IO.Path]::GetFullPath($RepoRoot)
    if (-not (Test-Path -LiteralPath (Join-Path $repository 'mvnw.cmd') -PathType Leaf)) {
        throw 'Maven policy requires a repository-owned mvnw.cmd.'
    }

    $allowedGoals = @('compile', 'test-compile', 'test', 'package', 'verify', 'spotless:check')
    $allowedFlags = @('-q', '--quiet', '-ntp', '--no-transfer-progress', '-U', '-e', '-ff', '-fae')
    $goalCount = 0
    foreach ($argument in $Arguments) {
        if ([string]::IsNullOrWhiteSpace($argument) -or $argument -match '[\r\n]') {
            throw 'Maven arguments must be non-empty single-line tokens.'
        }
        if ($allowedGoals -contains $argument) {
            $goalCount++
            continue
        }
        if ($allowedFlags -contains $argument) { continue }
        if ($argument -match '^-P[A-Za-z0-9_.-]+(?:,[A-Za-z0-9_.-]+)*$') { continue }
        if ($argument -match '^-D(?<name>[A-Za-z0-9_.-]+)(?:=.*)?$') {
            $name = $Matches.name.ToLowerInvariant()
            if ($name -in @('basedir', 'user.dir', 'java.io.tmpdir', 'maven.repo.local',
                    'maven.multimoduleprojectdirectory', 'maven.projectbasedir')) {
                throw "Maven property $name may redirect execution or writes outside the verified worktree."
            }
            continue
        }
        throw "Maven argument is not allowed by the repository tool policy: $argument"
    }
    if ($goalCount -ne 1) {
        throw 'Exactly one allowlisted Maven lifecycle goal is required.'
    }
}

function Assert-ProjectEnvironmentStateRoot {
    param(
        [AllowNull()][string]$StateRoot,
        [Parameter(Mandatory)][string]$AllowedRoot
    )
    if ([string]::IsNullOrWhiteSpace($StateRoot)) { return }
    $candidate = [IO.Path]::GetFullPath($StateRoot).TrimEnd('\')
    $allowed = [IO.Path]::GetFullPath($AllowedRoot).TrimEnd('\')
    if (-not [string]::Equals($candidate, $allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The repository entrypoint may only use its managed environment-state root.'
    }
}

function Resolve-ProjectManagedWorktree {
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$Worktree
    )
    $project = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
    $candidate = [IO.Path]::GetFullPath($Worktree).TrimEnd('\')
    $managedRoot = (Join-Path $project 'var\worktrees').TrimEnd('\')
    $isProjectRoot = [string]::Equals($candidate, $project, [StringComparison]::OrdinalIgnoreCase)
    $isManagedChild = $candidate.StartsWith("$managedRoot\", [StringComparison]::OrdinalIgnoreCase)
    if (-not $isProjectRoot -and -not $isManagedChild) {
        throw 'Managed lifecycle operations are limited to this repository and var/worktrees descendants.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $candidate '.git'))) {
        throw 'The requested target is not a Git worktree for this project.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $candidate 'scripts\.dev-state.json') -PathType Leaf)) {
        throw 'The requested worktree has no lifecycle state file.'
    }
    return $candidate
}


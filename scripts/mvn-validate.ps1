<#
.SYNOPSIS
  Runs Maven validate quietly. Successful Maven output stays in memory and is discarded.
#>

. "$PSScriptRoot\_maven-quiet.ps1"

$exitCode = Invoke-WithMavenWorktreeGitContext -RepoRoot $MavenRepoRoot -Runner {
    Invoke-QuietMaven `
        -Arguments @('-q', '-ntp', '-Dstyle.color=never', 'validate') `
        -SuccessMessage 'Maven validate passed'
}
exit $exitCode

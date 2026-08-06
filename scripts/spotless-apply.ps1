<#
.SYNOPSIS
  Applies the import-only Spotless rules. Run explicitly; Maven lifecycle phases only check them.
#>

[CmdletBinding()]
param([string]$SpotlessFiles)

. "$PSScriptRoot\_maven-quiet.ps1"
. "$PSScriptRoot\coordination-maven.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
try {
    $spotlessArguments = @('-q', '-ntp', '-Dstyle.color=never')
    if ($SpotlessFiles) { $spotlessArguments += "-DspotlessFiles=$SpotlessFiles" }
    $spotlessArguments += 'spotless:apply'
    $coordination = Invoke-WithMavenWorktreeGitContext -RepoRoot $repoRoot -Runner {
        Invoke-CoordinatedMavenOperation `
            -Application root `
            -Worktree $repoRoot `
            -Operation SpotlessApply `
            -SourceWrite `
            -Runner { Invoke-QuietMaven -Arguments $spotlessArguments -SuccessMessage 'Spotless import cleanup passed' }
    }
    if ($coordination.Outcome -eq 'BUSY') {
        throw 'Spotless source-write claim is busy; another Maven writer or source mutation is active.'
    }
    $exitCode = [int]$coordination.ExitCode
} catch {
    Write-Output ("Spotless runner 失敗：{0}" -f $_.Exception.Message)
    $exitCode = 1
}
exit $exitCode

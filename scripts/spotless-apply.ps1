<#
.SYNOPSIS
  Applies the import-only Spotless rules. Run explicitly; Maven lifecycle phases only check them.
#>

. "$PSScriptRoot\_maven-quiet.ps1"

$exitCode = Invoke-QuietMaven `
    -Arguments @('-q', '-ntp', '-Dstyle.color=never', 'spotless:apply') `
    -SuccessMessage 'Spotless import cleanup passed'
exit $exitCode

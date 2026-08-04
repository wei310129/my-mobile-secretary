[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$ReleaseGate,
    [Parameter(Mandatory)][ValidateSet('Manual','Automatic')][string]$Mode,
    [Parameter(Mandatory)][string]$EvidencePath,
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,31}$')][string]$MachineAlias,
    [string[]]$RequiredCapability = @(),
    [ValidateRange(1,168)][int]$MaximumAgeHours = 48,
    [switch]$RequireTracked
)

$ErrorActionPreference='Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot=Split-Path -Parent $PSScriptRoot
$allowedRoot=[IO.Path]::GetFullPath((Join-Path $repoRoot 'docs\exec-plans\evidence\development-environment'))
$resolved=if([IO.Path]::IsPathRooted($EvidencePath)){[IO.Path]::GetFullPath($EvidencePath)}else{[IO.Path]::GetFullPath((Join-Path $repoRoot $EvidencePath))}
$allowedPrefix=$allowedRoot.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
if(-not $resolved.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)){throw "Environment review evidence must stay under $allowedRoot"}
$review=Read-EnvironmentJson -Path $resolved
if(-not $review){throw "Environment review evidence is missing: $resolved"}
$validation=Assert-EnvironmentReviewDocument -Review $review -ReleaseGate $ReleaseGate -Mode $Mode `
    -RepoRoot $repoRoot -MachineAlias $MachineAlias -RequiredCapability $RequiredCapability `
    -MaximumAgeHours $MaximumAgeHours
if($RequireTracked){
    $relative=$resolved.Substring($repoRoot.Length).TrimStart('\','/')
    & git -C $repoRoot ls-files --error-unmatch -- $relative 2>$null|Out-Null
    if($LASTEXITCODE -ne 0){throw 'Environment review evidence is not tracked by Git.'}
}
$validation|ConvertTo-Json -Compress

[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$ReleaseGate,
    [Parameter(Mandatory)][ValidateSet('Manual','Automatic')][string]$Mode,
    [Parameter(Mandatory)][string]$EvidencePath,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{0,31}$')][string]$MachineAlias,
    [string]$TargetWorktree,
    [string]$EvidenceRoot,
    [string]$StateRoot,
    [string[]]$RequiredCapability = @(),
    [ValidateRange(1,168)][int]$MaximumAgeHours = 48,
    [switch]$RequireTracked
)

$ErrorActionPreference='Stop'
. "$PSScriptRoot\environment-common.ps1"
$anchorRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = if ($TargetWorktree) { [IO.Path]::GetFullPath($TargetWorktree) } else { $anchorRoot }
$targetIdentity = Assert-EnvironmentTargetWorktree -TargetWorktree $targetRoot -AnchorWorktree $anchorRoot
$approvedRoot = [IO.Path]::GetFullPath((Join-Path $targetIdentity.Path 'docs\exec-plans\evidence\development-environment'))
$evidenceRootResolved = if ($EvidenceRoot) { [IO.Path]::GetFullPath($EvidenceRoot) } else { $approvedRoot }
$approvedPrefix = $approvedRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if (-not ($evidenceRootResolved.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar).StartsWith($approvedPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw "Environment review evidence root escaped the target worktree: $approvedRoot"
}
$resolved = if ([IO.Path]::IsPathRooted($EvidencePath)) {
    [IO.Path]::GetFullPath($EvidencePath)
} elseif ($EvidencePath -match '^(?i:docs[\\/])') {
    [IO.Path]::GetFullPath((Join-Path $targetIdentity.Path $EvidencePath))
} else {
    [IO.Path]::GetFullPath((Join-Path $evidenceRootResolved $EvidencePath))
}
if (-not $resolved.StartsWith($approvedPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw "Environment review evidence escaped the target worktree: $approvedRoot" }
$review=Read-EnvironmentJson -Path $resolved
if(-not $review){throw "Environment review evidence is missing: $resolved"}
$validationArguments=@{
    Review=$review; ReleaseGate=$ReleaseGate; Mode=$Mode; RepoRoot=$targetIdentity.Path
    TargetWorktree=$targetIdentity.Path; AnchorWorktree=$anchorRoot; EvidenceRoot=$evidenceRootResolved
    MachineAlias=$MachineAlias; RequiredCapability=$RequiredCapability; MaximumAgeHours=$MaximumAgeHours
}
if($StateRoot){$validationArguments.StateRoot=$StateRoot}
$validation=Assert-EnvironmentReviewDocument @validationArguments
if($RequireTracked){
    $relative=$resolved.Substring($targetIdentity.Path.Length).TrimStart('\','/')
    & git -C $targetIdentity.Path ls-files --error-unmatch -- $relative 2>$null | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Environment review evidence is not tracked by Git.'}
}
$validation|ConvertTo-Json -Compress

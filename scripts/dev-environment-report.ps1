[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Z][A-Z0-9_]{2,63}$')][string]$Code,
    [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
    [string]$Expected,[string]$Actual,[ValidateSet('MANUAL','CAPABILITY','JAVA_HOME','MANAGED_OPERATION')][string]$RecheckKind='MANUAL',
    [ValidateSet('PROBE_ONLY','OPERATION_PARTICIPANT')][string]$Participation='PROBE_ONLY',
    [switch]$Resolve,[ValidateSet('FIXED','RESOLVED_BY_PROJECT_EVOLUTION')][string]$ResolutionStatus,
    [switch]$AcceptedLimitation,[ValidateSet('SANDBOX_ASYNC_TOOL_REQUIRED')][string]$LimitationPolicyCode,
    [ValidateSet('host','sandbox')][string]$CallerKind,
    [string]$TargetWorktree,[string]$StateRoot,[string]$MachineAlias,[switch]$Json
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\environment-common.ps1"
if(-not $CallerKind){$CallerKind=(Get-EnvironmentCallerContext).Kind}
$toolingRoot=Split-Path -Parent $PSScriptRoot
$targetRoot=if($TargetWorktree){[IO.Path]::GetFullPath($TargetWorktree)}else{$toolingRoot}
$identity=Assert-EnvironmentTargetWorktree -TargetWorktree $targetRoot -AnchorWorktree $toolingRoot
if($Resolve -and $AcceptedLimitation){throw 'resolve and accepted limitation actions are mutually exclusive'}
if($Resolve -and -not $ResolutionStatus){throw 'resolution status is required for verified resolution'}
if(-not $Resolve -and $ResolutionStatus){throw 'resolution status requires -Resolve'}
if($AcceptedLimitation -and -not $LimitationPolicyCode){throw 'accepted limitation requires an approved limitation policy code'}
if(-not $AcceptedLimitation -and $LimitationPolicyCode){throw 'limitation policy code requires -AcceptedLimitation'}
$common=@{Code=$Code;Capability=$Capability;RepoRoot=$identity.Path}
if($StateRoot){$common.StateRoot=$StateRoot};if($MachineAlias){$common.MachineAlias=$MachineAlias}
if($Resolve){$issue=Resolve-EnvironmentIssue @common -Status $ResolutionStatus -CallerKind $CallerKind}
elseif($AcceptedLimitation){$issue=Resolve-EnvironmentIssue @common -Status ACCEPTED_LIMITATION -LimitationPolicyCode $LimitationPolicyCode -CallerKind $CallerKind}
else{
    if([string]::IsNullOrWhiteSpace($Expected) -or [string]::IsNullOrWhiteSpace($Actual)){throw 'OPEN issue report requires expected and actual observations'}
    $issue=Write-EnvironmentIssue @common -Expected $Expected -Actual $Actual -RecheckKind $RecheckKind -Participation $Participation
}
if($Json){$issue|ConvertTo-Json -Depth 8}else{Write-Host ("Environment issue lifecycle updated: code={0}; capability={1}; status={2}; fingerprint={3}" -f $issue.code,$issue.capability,$issue.status,$issue.fingerprint)}

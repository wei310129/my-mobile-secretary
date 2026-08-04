[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Code,
    [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
    [Parameter(Mandatory)][string]$Expected,
    [Parameter(Mandatory)][string]$Actual,
    [ValidateSet('MANUAL','CAPABILITY','JAVA_HOME')][string]$RecheckKind = 'MANUAL',
    [switch]$AcceptedLimitation,
    [string]$ResolutionEvidence,
    [string]$StateRoot,
    [string]$MachineAlias,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$arguments = @{
    Code=$Code;Capability=$Capability;Expected=$Expected;Actual=$Actual;RecheckKind=$RecheckKind
    Status=if($AcceptedLimitation){'ACCEPTED_LIMITATION'}else{'OPEN'}
    ResolutionEvidence=$ResolutionEvidence;RepoRoot=(Split-Path -Parent $PSScriptRoot)
}
if ($StateRoot) { $arguments.StateRoot = $StateRoot }
if ($MachineAlias) { $arguments.MachineAlias = $MachineAlias }
$issue = Write-EnvironmentIssue @arguments
if ($Json) { $issue | ConvertTo-Json -Depth 6 }
else { Write-Host ("Environment issue recorded: {0}; fingerprint={1}; occurrences={2}" -f $Code,$issue.fingerprint,$issue.occurrences) }

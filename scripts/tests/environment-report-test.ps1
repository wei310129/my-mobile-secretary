[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scriptsRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')
$repoRoot=Split-Path -Parent $scriptsRoot
$stateRoot=Join-Path ([IO.Path]::GetTempPath()) ("mms-report-" + [guid]::NewGuid().ToString('n').Substring(0,8))
$assertions=0
function Assert-ReportTest([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:assertions++}
function Invoke-Report([string[]]$Arguments){
    $previous=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try{$out=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsRoot 'dev-environment-report.ps1') @Arguments 2>$null;$code=[int]$LASTEXITCODE}
    finally{$ErrorActionPreference=$previous}
    [pscustomobject]@{ExitCode=$code;Output=($out -join "`n")}
}
try{
    $opened=Invoke-Report @('-Code','TEST_MANUAL_RECHECK','-Capability','READ_ONLY','-Expected','ready','-Actual','blocked',
        '-RecheckKind','CAPABILITY','-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest ($opened.ExitCode -eq 0) 'typed OPEN issue report failed'
    $issue=$opened.Output|ConvertFrom-Json
    Assert-ReportTest ($issue.status -eq 'OPEN') 'new report did not create OPEN issue'
    $badCode=Invoke-Report @('-Code','not typed','-Capability','READ_ONLY','-Expected','ready','-Actual','blocked',
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop')
    Assert-ReportTest ($badCode.ExitCode -ne 0) 'untyped issue code was accepted'
    $arbitraryLimitation=Invoke-Report @('-Code','TEST_MANUAL_RECHECK','-Capability','READ_ONLY','-AcceptedLimitation',
        '-LimitationPolicyCode','SANDBOX_ASYNC_TOOL_REQUIRED','-StateRoot',$stateRoot,'-MachineAlias','test-laptop')
    Assert-ReportTest ($arbitraryLimitation.ExitCode -ne 0) 'arbitrary accepted limitation evidence was accepted'
    $ready=[pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;Version='fake'}
    New-EnvironmentSnapshot -Capability READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop `
        -ProbeOverrides @{PowerShell=$ready;Git=$ready}|Out-Null
    $fixed=Invoke-Report @('-Code','TEST_MANUAL_RECHECK','-Capability','READ_ONLY','-Resolve','-ResolutionStatus','FIXED',
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest ($fixed.ExitCode -eq 0) 'manual issue could not be formally revalidated'
    $fixedIssue=$fixed.Output|ConvertFrom-Json
    Assert-ReportTest ($fixedIssue.status -eq 'FIXED' -and $fixedIssue.resolutionEvidence.kind -eq 'FIXED') 'manual resolution lacked structured matching evidence'
    $asyncOpen=Invoke-Report @('-Code','ASYNC_AGENT_LAUNCH_REQUIRED','-Capability','READ_ONLY','-Expected','durable monitor','-Actual','sandbox boundary',
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest ($asyncOpen.ExitCode -eq 0) 'accepted-limitation fixture could not be opened'
    $accepted=Invoke-Report @('-Code','ASYNC_AGENT_LAUNCH_REQUIRED','-Capability','READ_ONLY','-AcceptedLimitation',
        '-LimitationPolicyCode','SANDBOX_ASYNC_TOOL_REQUIRED','-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest ($accepted.ExitCode -eq 0) 'approved accepted limitation could not be recorded'
    $observedAgain=Invoke-Report @('-Code','ASYNC_AGENT_LAUNCH_REQUIRED','-Capability','READ_ONLY','-Expected','durable monitor','-Actual','sandbox boundary',
        '-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest (($observedAgain.Output|ConvertFrom-Json).status -eq 'ACCEPTED_LIMITATION') 'ordinary observation silently reopened accepted limitation'
    $privacy=Invoke-Report @('-Code','PRIVACY_BOUNDARY_TEST','-Capability','READ_ONLY','-Expected','private-safe',
        '-Actual',("$repoRoot token="+('secret-'+'value')+' host='+[Environment]::MachineName),'-StateRoot',$stateRoot,'-MachineAlias','test-laptop','-Json')
    Assert-ReportTest ($privacy.ExitCode -eq 0) 'privacy fixture report failed'
    $serialized=$privacy.Output
    Assert-ReportTest (-not $serialized.Contains($repoRoot) -and -not $serialized.Contains([Environment]::UserName) -and -not $serialized.Contains([Environment]::MachineName)) 'report leaked private local identity'
    Assert-ReportTest (-not $serialized.Contains('secret-value')) 'report leaked credential-like content'
    [pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress
}finally{if(Test-Path -LiteralPath $stateRoot){Remove-Item -LiteralPath $stateRoot -Recurse -Force}}

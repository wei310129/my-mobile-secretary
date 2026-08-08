[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$ReleaseGate,
    [ValidateSet('Manual','Automatic')][string]$Mode = 'Automatic',
    [string[]]$Capability = @('READ_ONLY','MAVEN'),
    [string]$TargetWorktree,
    [string]$EvidenceRoot,
    [string]$EvidencePath,
    [string]$StateRoot,
    [string]$MachineAlias,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"

$toolingRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = if ($TargetWorktree) { [IO.Path]::GetFullPath($TargetWorktree) } else { $toolingRoot }
$targetIdentity = Assert-EnvironmentTargetWorktree -TargetWorktree $targetRoot -AnchorWorktree $toolingRoot
$contextArguments = @{ RepoRoot=$targetIdentity.Path }
if ($StateRoot) { $contextArguments.StateRoot=$StateRoot }
if ($MachineAlias) { $contextArguments.MachineAlias=$MachineAlias }
$context = Get-EnvironmentStateContext @contextArguments
$effectiveAlias = Assert-EnvironmentMachineAlias -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias

$approvedEvidenceRoot = [IO.Path]::GetFullPath((Join-Path $targetIdentity.Path 'docs\exec-plans\evidence\development-environment'))
$resolvedEvidenceRoot = if ($EvidenceRoot) { [IO.Path]::GetFullPath($EvidenceRoot) } else { $approvedEvidenceRoot }
$approvedPrefix = $approvedEvidenceRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
$resolvedRootPrefix = $resolvedEvidenceRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedRootPrefix.StartsWith($approvedPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw "EvidenceRoot must stay inside the target worktree approved directory: $approvedEvidenceRoot"
}
$resolvedEvidence = if ($EvidencePath) {
    if ([IO.Path]::IsPathRooted($EvidencePath)) { [IO.Path]::GetFullPath($EvidencePath) } else { [IO.Path]::GetFullPath((Join-Path $resolvedEvidenceRoot $EvidencePath)) }
} else {
    Join-Path $resolvedEvidenceRoot "$ReleaseGate.json"
}
if (-not $resolvedEvidence.StartsWith($approvedPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw "EvidencePath must stay inside the target worktree approved directory: $approvedEvidenceRoot"
}

$capabilityNames = [Collections.Generic.List[string]]::new()
foreach ($entry in @($Capability)) {
    foreach ($name in @([string]$entry -split ',')) {
        $normalized = $name.Trim().ToUpperInvariant()
        if ($script:EnvironmentCapabilityNames -notcontains $normalized) { throw "Unsupported environment capability: $name" }
        if (-not $capabilityNames.Contains($normalized)) { $capabilityNames.Add($normalized) }
    }
}

$results = [Collections.Generic.List[object]]::new()
foreach ($name in $capabilityNames) {
    $arguments = @{Capability=$name;RepoRoot=$targetIdentity.Path;RequireFresh=$true;ApplyProcessJava=$true;SkipDemandWrite=$true}
    if ($StateRoot) { $arguments.StateRoot=$StateRoot }
    if ($MachineAlias) { $arguments.MachineAlias=$MachineAlias }
    $results.Add((Invoke-EnvironmentPreflight @arguments))
}

$currentContract = Get-EnvironmentContract -RepoRoot $targetIdentity.Path
$currentCallerKind = (Get-EnvironmentCallerContext).Kind
$activeServiceGeneration = Get-EnvironmentActiveServiceGeneration -RepoRoot $targetIdentity.Path
$issueReviews = [Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $context.IssuesPath -PathType Container) {
    foreach ($file in @(Get-ChildItem -LiteralPath $context.IssuesPath -Filter *.json -File)) {
        $issue = Read-EnvironmentJson -Path $file.FullName
        if(-not $issue.PSObject.Properties['worktreeId'] -or $issue.worktreeId -ne $targetIdentity.WorktreeId){continue}
        if($capabilityNames -notcontains [string]$issue.capability){continue}
        $current = if ($issue.callerKind -eq $currentCallerKind) {
            @($results | Where-Object { $_.requestedCapability -eq $issue.capability } | Select-Object -First 1)
        } else {
            $callerSnapshotPath = Get-EnvironmentSnapshotPath -Context $context -Capability $issue.capability -CallerKind $issue.callerKind
            $callerSnapshot = Read-EnvironmentJson -Path $callerSnapshotPath
            if ($callerSnapshot -and $callerSnapshot.caller.Kind -eq $issue.callerKind -and
                    $callerSnapshot.worktreeId -eq $targetIdentity.WorktreeId -and
                    (Test-EnvironmentSnapshotFresh -Snapshot $callerSnapshot -Capability $issue.capability -RepoRoot $targetIdentity.Path -CallerKind $issue.callerKind -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias)) {
                @($callerSnapshot)
            } else { @() }
        }
        $current = @($current)
        $classification = if(Test-EnvironmentIssueResolutionEvidence -Issue $issue -Context $context -RepoRoot $targetIdentity.Path){[string]$issue.status}else{'OPEN'}
        $recheckKind = if ($issue.PSObject.Properties['recheckKind']) { [string]$issue.recheckKind } else { 'MANUAL' }
        $participation = if($issue.PSObject.Properties['participation']){[string]$issue.participation}elseif([string]$issue.code -like 'PREFLIGHT_*'){'PROBE_ONLY'}else{'OPERATION_PARTICIPANT'}
        $managedEvidence=$null
        $managedEligible=$classification -eq 'OPEN' -and $participation -eq 'PROBE_ONLY' -and
            $issue.code -in @('PREFLIGHT_CALLER_ACCESS_DENIED','PREFLIGHT_HOST_READY_CALLER_BLOCKED') -and
            $script:ManagedOperationCapabilities.ContainsKey([string]$issue.capability) -and $activeServiceGeneration
        if($managedEligible){
            $operation=$script:ManagedOperationCapabilities[[string]$issue.capability]
            $managedEvidence=Find-EnvironmentManagedOperationReceipt -Capability $issue.capability -Operation $operation `
                -Generation $activeServiceGeneration -RepoRoot $targetIdentity.Path -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias
            if($managedEvidence){
                $issue=Resolve-EnvironmentIssueByManagedOperation -Issue $issue -ReceiptPath $managedEvidence.Path `
                    -Generation $activeServiceGeneration -RepoRoot $targetIdentity.Path -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias
                $classification='FIXED';$recheckKind='MANAGED_OPERATION'
            }
        }
        $recheckReady = $false
        if ($current.Count -gt 0 -and $recheckKind -eq 'CAPABILITY') {
            $recheckReady = [bool]$current[0].capability.Ready
        } elseif ($current.Count -gt 0 -and $recheckKind -eq 'JAVA_HOME') {
            $processRepairApplied = $current[0].probes.Java.PSObject.Properties['ProcessRepairApplied'] -and [bool]$current[0].probes.Java.ProcessRepairApplied
            $persistentReady = $current[0].probes.Java.PSObject.Properties['PersistentConfigurationReady'] -and [bool]$current[0].probes.Java.PersistentConfigurationReady
            $recheckReady = [bool]$current[0].probes.Java.Ready -and (-not $processRepairApplied -or $persistentReady)
        }
        if ($classification -eq 'OPEN' -and $recheckKind -in @('CAPABILITY','JAVA_HOME') -and $recheckReady) {
            $classification = if ($issue.contractFingerprint -ne $currentContract.Fingerprint) { 'RESOLVED_BY_PROJECT_EVOLUTION' } else { 'FIXED' }
            Resolve-EnvironmentIssue -Code $issue.code -Capability $issue.capability -Status $classification `
                -CallerKind $issue.callerKind -Snapshot $current[0] -RepoRoot $targetIdentity.Path `
                -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
        }
        $issueReviews.Add([pscustomobject]@{
            fingerprint=$issue.fingerprint; code=$issue.code; capability=$issue.capability
            classification=$classification; participation=$participation; lastSeen=$issue.lastSeen
            evidence=if($classification -eq 'ACCEPTED_LIMITATION'){$issue.resolutionEvidence}elseif($managedEvidence){[ordered]@{kind='MANAGED_OPERATION_SUPERSESSION';operationCaller='host';issueCaller=$issue.callerKind;capability=$issue.capability;generation=$activeServiceGeneration;receiptFingerprint=$managedEvidence.Validation.ReceiptFingerprint}}elseif($current.Count -gt 0){$current[0].capability.State}else{"matching $($issue.callerKind) caller recheck required"}
        })
    }
}
$openCapabilities = @($results | Where-Object { -not $_.capability.Ready })
$openIssues = @($issueReviews | Where-Object { $_.classification -eq 'OPEN' })
$outcome = if ($openCapabilities.Count -eq 0 -and $openIssues.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
$relativeEvidencePath = $resolvedEvidence.Substring($targetIdentity.Path.Length).TrimStart('\','/').Replace('\','/')
$relativeEvidenceRoot = $resolvedEvidenceRoot.Substring($targetIdentity.Path.Length).TrimStart('\','/').Replace('\','/')
$review = [ordered]@{
    schemaVersion=$script:EnvironmentSchemaVersion
    releaseGate=$ReleaseGate
    mode=$Mode
    outcome=$outcome
    machineAlias=$effectiveAlias
    reviewedAt=[datetime]::UtcNow.ToString('o')
    contractFingerprint=$currentContract.Fingerprint
    targetWorktree=[ordered]@{
        worktreeId=$targetIdentity.WorktreeId; repoId=$targetIdentity.RepoId
        branch=$targetIdentity.Branch; head=$targetIdentity.Head; registered=[bool]$targetIdentity.Registered
    }
    evidencePath=$relativeEvidencePath
    evidenceRoot=$relativeEvidenceRoot
    capabilities=@($results|ForEach-Object{[ordered]@{name=$_.requestedCapability;state=$_.capability.State;ready=[bool]$_.capability.Ready;reason=$_.capability.Reason}})
    issues=@($issueReviews)
    openCount=$openCapabilities.Count+$openIssues.Count
    policy=if($Mode -eq 'Manual'){'W10 manual review receipt'}else{'W11+ automatic hard gate'}
}
Write-CoordinationJsonAtomic -Path (Join-Path $context.ReviewsPath "$ReleaseGate.json") -Document $review
Write-CoordinationJsonAtomic -Path $resolvedEvidence -Document $review
if ($Json) { [pscustomobject]$review | ConvertTo-Json -Depth 12 }
else { Write-Host ("Environment release review: gate={0}; mode={1}; target={2}; outcome={3}; open={4}" -f $ReleaseGate,$Mode,$targetIdentity.Path,$outcome,$review.openCount) }
if ($outcome -eq 'PASS') { exit 0 }
if ($Mode -eq 'Manual') { exit 10 }
exit 40

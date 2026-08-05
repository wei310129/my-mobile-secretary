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
$issueReviews = [Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $context.IssuesPath -PathType Container) {
    foreach ($file in @(Get-ChildItem -LiteralPath $context.IssuesPath -Filter *.json -File)) {
        $issue = Read-EnvironmentJson -Path $file.FullName
        $current = if ($issue.callerKind -eq $currentCallerKind) {
            @($results | Where-Object { $_.requestedCapability -eq $issue.capability } | Select-Object -First 1)
        } else {
            $callerSnapshotPath = Get-EnvironmentSnapshotPath -Context $context -Capability $issue.capability -CallerKind $issue.callerKind
            $callerSnapshot = Read-EnvironmentJson -Path $callerSnapshotPath
            if ($callerSnapshot -and $callerSnapshot.caller.Kind -eq $issue.callerKind -and
                    (Test-EnvironmentSnapshotFresh -Snapshot $callerSnapshot -Capability $issue.capability -RepoRoot $targetIdentity.Path)) {
                @($callerSnapshot)
            } else { @() }
        }
        $current = @($current)
        $classification = 'OPEN'
        $recheckKind = if ($issue.PSObject.Properties['recheckKind']) { [string]$issue.recheckKind } else { 'MANUAL' }
        $recheckReady = $false
        if ($current.Count -gt 0 -and $recheckKind -eq 'CAPABILITY') {
            $recheckReady = [bool]$current[0].capability.Ready
        } elseif ($current.Count -gt 0 -and $recheckKind -eq 'JAVA_HOME') {
            $processRepairApplied = $current[0].probes.Java.PSObject.Properties['ProcessRepairApplied'] -and [bool]$current[0].probes.Java.ProcessRepairApplied
            $persistentReady = $current[0].probes.Java.PSObject.Properties['PersistentConfigurationReady'] -and [bool]$current[0].probes.Java.PersistentConfigurationReady
            $recheckReady = [bool]$current[0].probes.Java.Ready -and (-not $processRepairApplied -or $persistentReady)
        }
        if ($recheckKind -in @('CAPABILITY','JAVA_HOME') -and $recheckReady) {
            $classification = if ($issue.contractFingerprint -ne $currentContract.Fingerprint) { 'RESOLVED_BY_PROJECT_EVOLUTION' } else { 'FIXED' }
        } elseif ($issue.status -eq 'ACCEPTED_LIMITATION') {
            $classification = 'ACCEPTED_LIMITATION'
        }
        $issueReviews.Add([pscustomobject]@{
            fingerprint=$issue.fingerprint; code=$issue.code; capability=$issue.capability
            classification=$classification; lastSeen=$issue.lastSeen
            evidence=if($classification -eq 'ACCEPTED_LIMITATION'){$issue.resolutionEvidence}elseif($current.Count -gt 0){$current[0].capability.State}else{"matching $($issue.callerKind) caller recheck required"}
        })
    }
}
$openCapabilities = @($results | Where-Object { -not $_.capability.Ready })
$openIssues = @($issueReviews | Where-Object { $_.classification -eq 'OPEN' })
$outcome = if ($openCapabilities.Count -eq 0 -and $openIssues.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
$review = [ordered]@{
    schemaVersion=$script:EnvironmentSchemaVersion
    releaseGate=$ReleaseGate
    mode=$Mode
    outcome=$outcome
    machineAlias=$effectiveAlias
    reviewedAt=[datetime]::UtcNow.ToString('o')
    contractFingerprint=$currentContract.Fingerprint
    targetWorktree=[ordered]@{
        path=$targetIdentity.Path; worktreeId=$targetIdentity.WorktreeId; repoId=$targetIdentity.RepoId
        primaryRoot=$targetIdentity.PrimaryRoot; commonDirectory=$targetIdentity.CommonDirectory
        branch=$targetIdentity.Branch; head=$targetIdentity.Head; registered=[bool]$targetIdentity.Registered
    }
    evidencePath=$resolvedEvidence
    evidenceRoot=$resolvedEvidenceRoot
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

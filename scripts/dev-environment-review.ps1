[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$ReleaseGate,
    [ValidateSet('Manual','Automatic')][string]$Mode = 'Automatic',
    [string[]]$Capability = @('READ_ONLY','MAVEN'),
    [string]$EvidencePath,
    [string]$StateRoot,
    [string]$MachineAlias,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$contextArguments = @{RepoRoot=$repoRoot}
if ($StateRoot) { $contextArguments.StateRoot=$StateRoot }
if ($MachineAlias) { $contextArguments.MachineAlias=$MachineAlias }
$context = Get-EnvironmentStateContext @contextArguments
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
    $arguments = @{Capability=$name;RepoRoot=$repoRoot;RequireFresh=$true;ApplyProcessJava=$true;SkipDemandWrite=$true}
    if ($StateRoot) { $arguments.StateRoot=$StateRoot }
    if ($MachineAlias) { $arguments.MachineAlias=$MachineAlias }
    $results.Add((Invoke-EnvironmentPreflight @arguments))
}

$currentContract = Get-EnvironmentContract -RepoRoot $repoRoot
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
                    (Test-EnvironmentSnapshotFresh -Snapshot $callerSnapshot -Capability $issue.capability -RepoRoot $repoRoot)) {
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
            $processRepairApplied = $current[0].probes.Java.PSObject.Properties['ProcessRepairApplied'] -and
                [bool]$current[0].probes.Java.ProcessRepairApplied
            $persistentReady = $current[0].probes.Java.PSObject.Properties['PersistentConfigurationReady'] -and
                [bool]$current[0].probes.Java.PersistentConfigurationReady
            $recheckReady = [bool]$current[0].probes.Java.Ready -and (-not $processRepairApplied -or $persistentReady)
        }
        if ($recheckKind -in @('CAPABILITY','JAVA_HOME') -and $recheckReady) {
            $classification = if ($issue.contractFingerprint -ne $currentContract.Fingerprint) { 'RESOLVED_BY_PROJECT_EVOLUTION' } else { 'FIXED' }
        } elseif ($issue.status -eq 'ACCEPTED_LIMITATION') {
            $classification = 'ACCEPTED_LIMITATION'
        }
        $issueReviews.Add([pscustomobject]@{
            fingerprint=$issue.fingerprint;code=$issue.code;capability=$issue.capability
            classification=$classification;lastSeen=$issue.lastSeen
            evidence=if($classification -eq 'ACCEPTED_LIMITATION'){$issue.resolutionEvidence}elseif($current.Count -gt 0){$current[0].capability.State}else{"matching $($issue.callerKind) caller recheck required"}
        })
    }
}
$openCapabilities = @($results | Where-Object { -not $_.capability.Ready })
$openIssues = @($issueReviews | Where-Object { $_.classification -eq 'OPEN' })
$outcome = if ($openCapabilities.Count -eq 0 -and $openIssues.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
$review = [ordered]@{
    schemaVersion=$script:EnvironmentSchemaVersion;releaseGate=$ReleaseGate;mode=$Mode;outcome=$outcome
    machineAlias=$context.MachineAlias;reviewedAt=[datetime]::UtcNow.ToString('o');contractFingerprint=$currentContract.Fingerprint
    capabilities=@($results|ForEach-Object{[ordered]@{name=$_.requestedCapability;state=$_.capability.State;ready=[bool]$_.capability.Ready;reason=$_.capability.Reason}})
    issues=@($issueReviews);openCount=$openCapabilities.Count+$openIssues.Count
    policy=if($Mode -eq 'Manual'){'W10 manual review receipt'}else{'W11+ automatic hard gate'}
}
$receiptPath = Join-Path $context.ReviewsPath "$ReleaseGate.json"
Write-CoordinationJsonAtomic -Path $receiptPath -Document $review

if ($EvidencePath) {
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'docs\exec-plans\evidence\development-environment'))
    $resolvedEvidence = if ([IO.Path]::IsPathRooted($EvidencePath)) { [IO.Path]::GetFullPath($EvidencePath) } else { [IO.Path]::GetFullPath((Join-Path $repoRoot $EvidencePath)) }
    $allowedPrefix = $allowedRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedEvidence.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)) {
        throw "EvidencePath must stay under $allowedRoot"
    }
    Write-CoordinationJsonAtomic -Path $resolvedEvidence -Document $review
}
if ($Json) { [pscustomobject]$review | ConvertTo-Json -Depth 10 }
else { Write-Host ("Environment release review: gate={0}; mode={1}; outcome={2}; open={3}" -f $ReleaseGate,$Mode,$outcome,$review.openCount) }
if ($outcome -eq 'PASS') { exit 0 }
if ($Mode -eq 'Manual') { exit 10 }
exit 40

[CmdletBinding()]
param(
    [ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')]
    [string]$Capability = 'READ_ONLY',
    [switch]$Async,
    [switch]$RequireFresh,
    [switch]$Json,
    [string]$StateRoot,
    [string]$MachineAlias
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
. "$PSScriptRoot\project-tool-policy.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$environmentLayout = Get-EnvironmentGitLayout -RepoRoot $repoRoot
$allowedStateRoot = Join-Path $environmentLayout.PrimaryRoot 'var\environment-state\v1'
Assert-ProjectEnvironmentStateRoot -StateRoot $StateRoot -AllowedRoot $allowedStateRoot
$arguments = @{ Capability=$Capability; RepoRoot=$repoRoot }
if ($StateRoot) { $arguments.StateRoot = $StateRoot }
if ($MachineAlias) { $arguments.MachineAlias = $MachineAlias }

try {
    if ($Async) {
        $contextArguments = @{ RepoRoot=$repoRoot }
        if ($StateRoot) { $contextArguments.StateRoot = $StateRoot }
        if ($MachineAlias) { $contextArguments.MachineAlias = $MachineAlias }
        $context = Get-EnvironmentStateContext @contextArguments
        Write-EnvironmentDemand -Context $context -Capability $Capability
        $cachedSnapshot = Read-EnvironmentJson -Path (Get-EnvironmentSnapshotPath -Context $context -Capability $Capability)
        $snapshot = if (-not $RequireFresh -and (Test-EnvironmentSnapshotFresh -Snapshot $cachedSnapshot -Capability $Capability -RepoRoot $repoRoot)) {
            $cachedSnapshot
        } else { $null }
        $asyncLauncher = $null
        $persistentRepairPending = $snapshot -and $snapshot.probes.Java.PSObject.Properties['PersistentRepairEligible'] -and
            [bool]$snapshot.probes.Java.PersistentRepairEligible
        if (-not $snapshot -or $persistentRepairPending) {
            $monitorArguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\dev-environment-monitor.ps1",'-Capability',$Capability,'-Once')
            if ($Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E')) { $monitorArguments += '-AllowSafeRepair' }
            if ($StateRoot) { $monitorArguments += @('-StateRoot',$StateRoot) }
            if ($MachineAlias) { $monitorArguments += @('-MachineAlias',$MachineAlias) }
            if ((Get-EnvironmentCallerContext).IsSandbox) {
                $asyncLauncher = 'AGENT_ASYNC_TOOL_REQUIRED'
                Write-EnvironmentIssue -Code 'ASYNC_AGENT_LAUNCH_REQUIRED' -Capability $Capability `
                    -Expected 'the caller can keep a detached monitor alive after preflight exits' `
                    -Actual 'Codex sandbox child lifetime is not durable after the parent tool process exits' `
                    -Status ACCEPTED_LIMITATION `
                    -ResolutionEvidence 'agent must run dev-environment-monitor.ps1 with its asynchronous tool execution and continue read-only work' `
                    -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
            } else {
                Start-Process -FilePath powershell.exe -ArgumentList $monitorArguments -WindowStyle Hidden | Out-Null
                $asyncLauncher = 'DETACHED_PROCESS'
            }
        }
        if (-not $snapshot) {
            $snapshot = [pscustomobject]@{
                schemaVersion=$script:EnvironmentSchemaVersion;requestedCapability=$Capability
                capability=[pscustomobject]@{State='UNKNOWN';Ready=$false;Reason=if($asyncLauncher -eq 'AGENT_ASYNC_TOOL_REQUIRED'){'async demand queued; sandbox agent tool launch is required'}else{'asynchronous refresh started; no matching cached snapshot exists'}}
            }
        }
        if ($asyncLauncher) { $snapshot | Add-Member -NotePropertyName asyncLauncher -NotePropertyValue $asyncLauncher -Force }
    } else {
        $snapshot = Invoke-EnvironmentPreflight @arguments -RequireFresh:$RequireFresh -ApplyProcessJava
    }
    if (-not $Async -and -not $snapshot.capability.Ready) {
        $issueArguments = @{
            Code="PREFLIGHT_$($snapshot.capability.State)";Capability=$Capability
            Expected='requested capability is ready for the current caller';Actual=[string]$snapshot.capability.Reason
            RecheckKind='CAPABILITY';RepoRoot=$repoRoot
        }
        if ($StateRoot) { $issueArguments.StateRoot=$StateRoot }
        if ($MachineAlias) { $issueArguments.MachineAlias=$MachineAlias }
        Write-EnvironmentIssue @issueArguments | Out-Null
    }
    if ($Json) { $snapshot | ConvertTo-Json -Depth 12 }
    else {
        Write-Host ("Environment preflight: capability={0}; state={1}; ready={2}" -f `
            $Capability, $snapshot.capability.State, $snapshot.capability.Ready)
        if ($snapshot.capability.Reason) { Write-Host ("  reason: {0}" -f $snapshot.capability.Reason) }
        if ($snapshot.PSObject.Properties['asyncLauncher']) { Write-Host ("  async launcher: {0}" -f $snapshot.asyncLauncher) }
    }
    if ($snapshot.capability.Ready) { exit 0 }
    if ($Async) { exit 0 }
    if ($snapshot.capability.State -eq 'UNKNOWN') { exit 10 }
    exit 40
} catch {
    if ($Json) { [pscustomobject]@{outcome='FAILED';capability=$Capability;error=$_.Exception.Message}|ConvertTo-Json }
    else { Write-Host ("Environment preflight failed: {0}" -f $_.Exception.Message) }
    exit 50
}

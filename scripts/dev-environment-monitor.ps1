[CmdletBinding()]
param(
    [ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')]
    [string]$Capability,
    [switch]$Once,
    [switch]$Scheduled,
    [switch]$PublishGitHub,
    [switch]$AllowSafeRepair,
    [string]$StateRoot,
    [string]$MachineAlias
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$contextArguments = @{ RepoRoot=$repoRoot }
if ($StateRoot) { $contextArguments.StateRoot = $StateRoot }
if ($MachineAlias) { $contextArguments.MachineAlias = $MachineAlias }
$context = Get-EnvironmentStateContext @contextArguments
$monitorMutex = New-CoordinationMutex "environment-monitor/$($context.RepoId)/$($context.MachineAlias)"
$monitorHeld = $false
try { $monitorHeld = Wait-CoordinationMutex -Mutex $monitorMutex -Deadline ([datetime]::UtcNow.AddSeconds($(if($Scheduled){5}else{60}))) } catch [Threading.AbandonedMutexException] { $monitorHeld = $true }
if (-not $monitorHeld) { $monitorMutex.Dispose(); exit 0 }

function Start-EnvironmentDockerDesktop {
    $result = Ensure-ManagedDockerDaemon -StartupTimeoutSeconds 180
    if ($result.Outcome -eq 'READY') { return $true }
    if ($result.Classification -eq 'AGENT_ASYNC_TOOL_REQUIRED') {
        Write-Host 'AGENT_ASYNC_TOOL_REQUIRED: keep the host managed Docker launcher monitor alive.'
    }
    return $false
}

try {
    if (-not $Capability) {
        $demand = Get-ActiveEnvironmentDemand -Context $context
        if ($demand) { $Capability = [string]$demand.capability }
        else { $Capability = 'READ_ONLY' }
    }
    $invokeArguments = @{ Capability=$Capability; RepoRoot=$repoRoot; RequireFresh=$true; ApplyProcessJava=$true; SkipDemandWrite=$true }
    if ($StateRoot) { $invokeArguments.StateRoot = $StateRoot }
    if ($MachineAlias) { $invokeArguments.MachineAlias = $MachineAlias }
    $cached = Read-EnvironmentJson -Path (Get-EnvironmentSnapshotPath -Context $context -Capability $Capability)
    $cachedFresh = Test-EnvironmentSnapshotFresh -Snapshot $cached -Capability $Capability -RepoRoot $repoRoot
    $cachedRepairPending = $cachedFresh -and $cached.probes.Java.PSObject.Properties['PersistentRepairEligible'] -and
        [bool]$cached.probes.Java.PersistentRepairEligible
    $cachedDockerRepairPending = $cachedFresh -and $AllowSafeRepair -and
        $Capability -in @('DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
        $cached.probes.Docker.CliReady -and -not $cached.probes.Docker.DaemonReady
    $snapshot = if ($cachedFresh -and -not $cachedRepairPending -and -not $cachedDockerRepairPending) {
        $cached
    } else {
        Invoke-EnvironmentPreflight @invokeArguments
    }

    $javaProcessRepairApplied = $snapshot.probes.Java.PSObject.Properties['ProcessRepairApplied'] -and
        [bool]$snapshot.probes.Java.ProcessRepairApplied
    $javaPersistentRepairEligible = $snapshot.probes.Java.PSObject.Properties['PersistentRepairEligible'] -and
        [bool]$snapshot.probes.Java.PersistentRepairEligible
    if ($AllowSafeRepair -and $javaProcessRepairApplied -and $javaPersistentRepairEligible -and
            -not (Get-EnvironmentCallerContext).IsSandbox) {
        $javaCandidate = [string]$snapshot.probes.Java.CandidateHome
        try {
            $repairArguments = @{PersistUserJavaHome=$true;JavaHome=$javaCandidate}
            if ($StateRoot) { $repairArguments.StateRoot=$StateRoot }
            if ($MachineAlias) { $repairArguments.MachineAlias=$MachineAlias }
            & "$PSScriptRoot\dev-environment-repair.ps1" @repairArguments | Out-Null
            if ([Environment]::GetEnvironmentVariable('JAVA_HOME','User') -ne $javaCandidate) {
                throw 'user-level JAVA_HOME does not match the verified candidate after repair'
            }
            $env:JAVA_HOME = $javaCandidate
            $snapshot = Invoke-EnvironmentPreflight @invokeArguments
        } catch {
            Write-EnvironmentIssue -Code 'JAVA_HOME_REPAIR_FAILED' -Capability $Capability `
                -Expected 'verified Java 21 candidate is persisted for the current Windows user' `
                -Actual $_.Exception.Message -RecheckKind JAVA_HOME `
                -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
        }
    }

    if (-not $snapshot.capability.Ready -and $AllowSafeRepair -and
            $Capability -in @('DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
            $snapshot.probes.Docker.CliReady -and -not $snapshot.probes.Docker.DaemonReady) {
        if (Start-EnvironmentDockerDesktop) { $snapshot = Invoke-EnvironmentPreflight @invokeArguments }
    }

    if ($PublishGitHub -and (Test-Path -LiteralPath $context.GitHubConfigPath -PathType Leaf)) {
        $powershell = Get-Command powershell.exe -ErrorAction Stop
        $publishResult = Invoke-EnvironmentProcess -FilePath $powershell.Source -Arguments @(
            '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\dev-environment-github.ps1",
            '-Publish','-StateRoot',$context.StateRoot,'-MachineAlias',$context.MachineAlias
        ) -TimeoutMilliseconds 30000
        if ($publishResult.Output) { Write-Host $publishResult.Output.Trim() }
        if ($publishResult.ExitCode -ne 0) {
            Write-EnvironmentIssue -Code 'GITHUB_STATUS_PUBLISH_FAILED' -Capability 'READ_ONLY' `
                -Expected 'sanitized status publish succeeds' -Actual "exit $($publishResult.ExitCode)" `
                -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
        }
    }
    if (-not $snapshot.capability.Ready) {
        Write-EnvironmentIssue -Code "PREFLIGHT_$($snapshot.capability.State)" -Capability $Capability `
            -Expected 'requested capability is ready for the current caller' -Actual ([string]$snapshot.capability.Reason) `
            -RecheckKind CAPABILITY -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
    }
    if (-not $Once) { Write-Host ("Environment monitor refreshed {0}: {1}" -f $Capability,$snapshot.capability.State) }
    if ($snapshot.capability.Ready) { exit 0 }
    exit 10
} catch {
    try {
        Write-EnvironmentIssue -Code 'ENVIRONMENT_MONITOR_FAILED' -Capability 'READ_ONLY' `
            -Expected 'monitor completes within its bounded probe budget' -Actual $_.Exception.Message `
            -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
    } catch { }
    Write-Host ("Environment monitor failed: {0}" -f $_.Exception.Message)
    exit 50
} finally {
    if ($monitorHeld) { $monitorMutex.ReleaseMutex() }
    $monitorMutex.Dispose()
}

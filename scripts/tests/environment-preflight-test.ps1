[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'environment-common.ps1')
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$testBase = Join-Path ([IO.Path]::GetTempPath()) 'mms-environment-test-state'
$testRoot = Join-Path $testBase ([guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$assertions = 0

function Assert-EnvironmentTest {
    param([bool]$Condition,[string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-FakeProbe {
    param([string]$State='MATCH',[bool]$Ready=$true,[string]$Reason=$null)
    return [pscustomobject]@{State=$State;Ready=$Ready;Reason=$Reason;Version='fake'}
}

$environmentText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'environment-common.ps1')
Assert-EnvironmentTest ($environmentText.Contains('. "$PSScriptRoot\managed-docker-desktop.ps1"')) 'environment preflight does not load the managed Docker identity module'

try {
    $ready = @{
        PowerShell=(New-FakeProbe);Git=(New-FakeProbe)
        Java=([pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;CandidateHome='C:\fake';MavenReady=$true})
        Docker=([pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;CliReady=$true;DaemonReady=$true})
        Runtime=(New-FakeProbe)
    }
    $readOnly = New-EnvironmentSnapshot -Capability READ_ONLY -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides $ready -NoWrite
    Assert-EnvironmentTest $readOnly.capability.Ready 'READ_ONLY fake snapshot was not ready'
    Assert-EnvironmentTest ($readOnly.capability.State -eq 'MATCH') 'READ_ONLY fake snapshot state was not MATCH'
    $staleSchema = $readOnly | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $staleSchema.schemaVersion = $script:EnvironmentSchemaVersion - 1
    Assert-EnvironmentTest (-not (Test-EnvironmentSnapshotFresh -Snapshot $staleSchema -Capability READ_ONLY -RepoRoot $repoRoot)) 'old snapshot schema was accepted as fresh'
    $staleContract = $readOnly | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $staleContract.contract.Fingerprint = 'stale'
    Assert-EnvironmentTest (-not (Test-EnvironmentSnapshotFresh -Snapshot $staleContract -Capability READ_ONLY -RepoRoot $repoRoot)) 'stale contract fingerprint was accepted as fresh'
    Assert-EnvironmentTest (-not (Test-EnvironmentSnapshotFresh -Snapshot $readOnly -Capability MAVEN -RepoRoot $repoRoot)) 'snapshot for another capability was accepted as fresh'
    New-EnvironmentSnapshot -Capability READ_ONLY -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides $ready | Out-Null
    $writtenContext = Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    Assert-EnvironmentTest (-not (Test-Path -LiteralPath (Get-EnvironmentDemandPath -Context $writtenContext -Capability READ_ONLY))) 'monitor-level snapshot creation extended requester demand'

    $layoutPrimary = Join-Path $testRoot 'layout-primary'
    $layoutCommon = Join-Path $layoutPrimary '.git'
    $layoutWorktree = Join-Path $testRoot 'layout-worktree'
    $layoutGitDirectory = Join-Path (Join-Path $layoutCommon 'worktrees') 'sample'
    [IO.Directory]::CreateDirectory($layoutGitDirectory) | Out-Null
    [IO.Directory]::CreateDirectory($layoutWorktree) | Out-Null
    [IO.File]::WriteAllText((Join-Path $layoutCommon 'config'), "[remote `"origin`"]`n`turl = https://github.com/example/environment-test.git`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $layoutGitDirectory 'commondir'), '../..', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $layoutWorktree '.git'), "gitdir: $layoutGitDirectory", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $layoutGitDirectory 'gitdir'), (Join-Path $layoutWorktree '.git'), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $layoutGitDirectory 'HEAD'), 'ref: refs/heads/fixture', [Text.UTF8Encoding]::new($false))
    [IO.Directory]::CreateDirectory((Join-Path $layoutCommon 'refs\heads')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $layoutCommon 'refs\heads\fixture'), ('a' * 40), [Text.UTF8Encoding]::new($false))
    $worktreeLayout = Get-EnvironmentGitLayout -RepoRoot $layoutWorktree
    Assert-EnvironmentTest ([string]::Equals($worktreeLayout.PrimaryRoot,$layoutPrimary,[StringComparison]::OrdinalIgnoreCase)) 'worktree layout did not resolve the primary root without spawning Git'
    $primaryIdentity = Get-EnvironmentRepositoryIdentity -RepoRoot $layoutPrimary
    $worktreeIdentity = Get-EnvironmentRepositoryIdentity -RepoRoot $layoutWorktree
    Assert-EnvironmentTest ($primaryIdentity.RepoId -eq $worktreeIdentity.RepoId) 'primary and linked worktree did not share a repository identity'
    $registeredWithoutGit=@(Get-EnvironmentRegisteredWorktrees -AnchorWorktree $layoutWorktree)
    $fixtureEntry=@($registeredWithoutGit|Where-Object{$_.Path -eq (Normalize-EnvironmentPath $layoutWorktree)})|Select-Object -First 1
    Assert-EnvironmentTest ($fixtureEntry -and $fixtureEntry.Head -eq ('a' * 40) -and $fixtureEntry.Branch -eq 'refs/heads/fixture') 'linked worktree metadata was not resolved without spawning Git'

    $drift = @{} + $ready
    $drift.Java = [pscustomobject]@{State='COMPATIBLE_DRIFT';Ready=$true;Reason=$null;CandidateHome='C:\other';MavenReady=$true}
    $maven = New-EnvironmentSnapshot -Capability MAVEN -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides $drift -NoWrite
    Assert-EnvironmentTest ($maven.capability.State -eq 'COMPATIBLE_DRIFT') 'compatible Java drift was not preserved'

    $blocked = @{} + $ready
    $blocked.Docker = [pscustomobject]@{
        State='HOST_READY_CALLER_BLOCKED';Ready=$false;Reason='sandbox denied';CliReady=$true;DaemonReady=$false
    }
    $docker = New-EnvironmentSnapshot -Capability DOCKER_TEST -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides $blocked -NoWrite
    Assert-EnvironmentTest (-not $docker.capability.Ready) 'caller-blocked Docker snapshot incorrectly passed'
    Assert-EnvironmentTest ($docker.capability.State -eq 'HOST_READY_CALLER_BLOCKED') 'caller-blocked Docker state was collapsed'

    $unknown = @{} + $ready
    $unknown.Docker = [pscustomobject]@{State='UNKNOWN';Ready=$false;Reason='daemon state cannot be observed';CliReady=$true;DaemonReady=$false}
    $unknownDocker = New-EnvironmentSnapshot -Capability DOCKER_TEST -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides $unknown -NoWrite
    Assert-EnvironmentTest ($unknownDocker.capability.State -eq 'UNKNOWN') 'unknown Docker state was incorrectly made actionable'

    $savedReceipt=$env:MMS_EXTERNAL_AUTHORITY_RECEIPT;$savedProvider=$env:MMS_EXTERNAL_PROVIDER
    $savedOperation=$env:MMS_EXTERNAL_OPERATION_CLASS;$savedScope=$env:MMS_EXTERNAL_AUTHORITY_SCOPE
    try {
        $env:MMS_EXTERNAL_AUTHORITY_RECEIPT='arbitrary-non-empty';$env:MMS_EXTERNAL_PROVIDER='TDX'
        $env:MMS_EXTERNAL_OPERATION_CLASS='ROUTE_QUERY';$env:MMS_EXTERNAL_AUTHORITY_SCOPE='READ_ONLY'
        $external = New-EnvironmentSnapshot -Capability EXTERNAL_PROVIDER -RepoRoot $repoRoot -StateRoot $testRoot `
            -MachineAlias test-laptop -ProbeOverrides $ready -NoWrite
        Assert-EnvironmentTest (-not $external.capability.Ready) 'arbitrary non-empty external authority receipt was accepted'
        $issuedAuthority=New-EnvironmentExternalAuthorityReceipt -Provider TDX -OperationClass ROUTE_QUERY -Scope READ_ONLY `
            -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
        $env:MMS_EXTERNAL_AUTHORITY_RECEIPT=$issuedAuthority.Path
        $externalReady=New-EnvironmentSnapshot -Capability EXTERNAL_PROVIDER -RepoRoot $repoRoot -StateRoot $testRoot `
            -MachineAlias test-laptop -ProbeOverrides $ready
        Assert-EnvironmentTest $externalReady.capability.Ready 'valid scoped external authority receipt did not satisfy preflight'
        Assert-EnvironmentTest (Test-EnvironmentSnapshotFresh -Snapshot $externalReady -Capability EXTERNAL_PROVIDER `
            -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop) 'matching scoped external snapshot was not reusable within its fence'
        $env:MMS_EXTERNAL_OPERATION_CLASS='BOOKING_CREATE';$env:MMS_EXTERNAL_PROVIDER='BOOKING';$env:MMS_EXTERNAL_AUTHORITY_SCOPE='MUTATION'
        Assert-EnvironmentTest (-not (Test-EnvironmentSnapshotFresh -Snapshot $externalReady -Capability EXTERNAL_PROVIDER `
            -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop)) 'external snapshot was reused for a different provider operation'
    } finally {
        $env:MMS_EXTERNAL_AUTHORITY_RECEIPT=$savedReceipt;$env:MMS_EXTERNAL_PROVIDER=$savedProvider
        $env:MMS_EXTERNAL_OPERATION_CLASS=$savedOperation;$env:MMS_EXTERNAL_AUTHORITY_SCOPE=$savedScope
    }

    $oldJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = Join-Path $testRoot 'missing-jdk'
        $java = Get-EnvironmentJavaProbe -RepoRoot $repoRoot
        Assert-EnvironmentTest ($java.State -eq 'ACTION_REQUIRED') 'invalid JAVA_HOME was not actionable'
        Assert-EnvironmentTest ([bool]$java.CandidateHome) 'valid PATH Java was not offered as a candidate'
        Assert-EnvironmentTest $java.MavenReady 'candidate Java did not validate Maven Wrapper'
    } finally { $env:JAVA_HOME = $oldJavaHome }

    $issue1 = Write-EnvironmentIssue -Code 'FAKE_DRIFT' -Capability MAVEN -Expected ready -Actual blocked `
        -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    $issue2 = Write-EnvironmentIssue -Code 'FAKE_DRIFT' -Capability MAVEN -Expected ready -Actual blocked `
        -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    Assert-EnvironmentTest ($issue1.fingerprint -eq $issue2.fingerprint) 'issue fingerprint was not stable'
    Assert-EnvironmentTest ($issue2.occurrences -eq 2) 'issue occurrences were not deduplicated'
    $accepted = Write-EnvironmentIssue -Code 'ASYNC_AGENT_LAUNCH_REQUIRED' -Capability READ_ONLY -Expected ready -Actual limited `
        -Status ACCEPTED_LIMITATION -LimitationPolicyCode SANDBOX_ASYNC_TOOL_REQUIRED `
        -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    $acceptedAgain = Write-EnvironmentIssue -Code 'ASYNC_AGENT_LAUNCH_REQUIRED' -Capability READ_ONLY -Expected ready -Actual limited `
        -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    Assert-EnvironmentTest ($acceptedAgain.status -eq 'ACCEPTED_LIMITATION') 'deduped observation silently reopened an accepted limitation'
    Assert-EnvironmentTest ($acceptedAgain.resolutionEvidence.policyCode -eq $accepted.resolutionEvidence.policyCode) 'accepted limitation lost its structured resolution evidence'

    $published = ConvertTo-PublishedEnvironmentSnapshot -Snapshot $maven
    $publishedJson = $published | ConvertTo-Json -Depth 10
    Assert-EnvironmentTest ($publishedJson -notmatch 'ConfiguredHome|CandidateHome|RepoRoot|COMPUTERNAME') 'published snapshot leaked local identifiers'
    Assert-EnvironmentTest ($published.machineAlias -eq 'test-laptop') 'published snapshot lost machine alias'

    $context = Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    $expectedSnapshotName = "snapshot-$((Get-EnvironmentCallerContext).Kind)-$($context.WorktreeId).json"
    Assert-EnvironmentTest ([IO.Path]::GetFileName($context.SnapshotPath) -eq $expectedSnapshotName) 'caller did not receive a caller-specific snapshot path'
    $expectedMavenSnapshotName = "snapshot-$((Get-EnvironmentCallerContext).Kind)-maven-$($context.WorktreeId).json"
    Assert-EnvironmentTest ([IO.Path]::GetFileName((Get-EnvironmentSnapshotPath -Context $context -Capability MAVEN)) -eq $expectedMavenSnapshotName) 'capability did not receive an isolated snapshot path'
    Assert-EnvironmentTest ((Get-EnvironmentSnapshotPath -Context $context -Capability MAVEN) -match [regex]::Escape($context.WorktreeId)) 'snapshot path was not fenced to the target worktree'
    Write-EnvironmentDemand -Context $context -Capability MAVEN -TtlMinutes 10
    $demand = Read-EnvironmentJson -Path (Get-EnvironmentDemandPath -Context $context -Capability MAVEN)
    Assert-EnvironmentTest ($demand.capability -eq 'MAVEN' -and [datetime]$demand.expiresAt -gt [datetime]::UtcNow) 'demand TTL was not durable'
    Write-EnvironmentDemand -Context $context -Capability READ_ONLY -TtlMinutes 10
    Assert-EnvironmentTest ((Get-ActiveEnvironmentDemand -Context $context).capability -eq 'MAVEN') 'later low-cost demand overwrote an active higher capability demand'

    [pscustomobject]@{status='passed';assertions=$assertions;liveDocker='skipped';liveGitHub='skipped'} | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}

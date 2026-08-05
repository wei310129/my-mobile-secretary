[CmdletBinding()]
param([switch]$RunLive)

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-docker-desktop.ps1')
$repoRoot = Split-Path -Parent $scripts
$assertions = 0

function Assert-DockerTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

$contents = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $scripts 'managed-docker-desktop.ps1')
$forbiddenTokens = @('DockerCli -Shutdown', 'Stop-Process', 'docker system prune', 'compose down -v')
foreach ($token in $forbiddenTokens) {
    Assert-DockerTest ($contents -notmatch [regex]::Escape($token)) "managed Docker helper contains forbidden token: $token"
}

if (-not $RunLive) {
    [pscustomobject]@{ status = 'passed'; assertions = $assertions; liveDocker = 'skipped'; resources = 'unchanged'; reason = 'live Docker lifecycle requires explicit -RunLive outside the sandbox' } |
        ConvertTo-Json -Compress
    exit 0
}

$desktopIdentity = Resolve-ManagedDockerDesktopIdentity
$beforeSnapshot = Get-ManagedDockerDesktopProcesses -DesktopIdentity $desktopIdentity
$before = [int]$beforeSnapshot.Count
$result = Invoke-ManagedDockerDesktop -RepoRoot $repoRoot -TimeoutSeconds 30
$afterSnapshot = Get-ManagedDockerDesktopProcesses -DesktopIdentity $desktopIdentity
$after = [int]$afterSnapshot.Count

Assert-DockerTest ($result.outcome -eq 'READY' -and $result.daemonReady) 'managed launcher did not prove Docker daemon READY'
Assert-DockerTest ($result.requestedCapability -eq 'DOCKER_TEST') 'receipt capability was not DOCKER_TEST'
Assert-DockerTest ($result.launchDisposition -eq 'already-running') 'ready Docker Desktop was started a second time'
Assert-DockerTest ($after -eq $before) 'idempotent launcher changed Docker Desktop process count'
Assert-DockerTest ($result.executablePath -eq $script:ManagedDockerDesktopPath) 'receipt executable path was not fixed allowlist path'
Assert-DockerTest ($result.executableSha256 -match '^[0-9a-f]{64}$') 'receipt lacked executable fingerprint'
Assert-DockerTest ($result.executableSigner -match '(?i)CN=Docker Inc') 'receipt lacked trusted Docker signer'
Assert-DockerTest ($result.dockerContext -and $result.serverVersion) 'receipt lacked Docker context/server readiness'
Assert-DockerTest ($result.representativeCommand -eq 'docker ps --format {{.ID}}') 'representative command was not recorded'

$localReceipt = Join-Path (Join-Path (Join-Path $repoRoot 'var\environment-state\v1') 'receipts') "$($result.operationId).json"
Assert-DockerTest (Test-Path -LiteralPath $localReceipt -PathType Leaf) 'local managed Docker receipt was not written'
$coordReceipt = Join-Path (Join-Path (Get-CoordinationDefaultRoot) 'receipts') "$($result.operationId).json"
Assert-DockerTest (Test-Path -LiteralPath $coordReceipt -PathType Leaf) 'coordination managed Docker receipt was not written'
$manifestPath = Get-CoordinationOperationPath (Get-CoordinationDefaultRoot) $result.operationId
Assert-DockerTest (Test-Path -LiteralPath $manifestPath -PathType Leaf) 'coordination operation manifest was not retained'
$manifest = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
Assert-DockerTest ($manifest.status -eq 'RELEASED') 'Docker daemon mutex operation was not released'

$fixture = Join-Path ([IO.Path]::GetTempPath()) "mms-docker-identity-$([guid]::NewGuid()).exe"
[IO.File]::WriteAllText($fixture, 'not a signed Docker executable', [Text.UTF8Encoding]::new($false))
$previousPath = $script:ManagedDockerDesktopPath
try {
    $script:ManagedDockerDesktopPath = $fixture
    $rejected = $false
    try { Resolve-ManagedDockerDesktopIdentity | Out-Null } catch { $rejected = $true }
    Assert-DockerTest $rejected 'tampered/non-allowlisted Docker Desktop identity was accepted'
} finally {
    $script:ManagedDockerDesktopPath = $previousPath
    if (Test-Path -LiteralPath $fixture -PathType Leaf) { Remove-Item -LiteralPath $fixture -Force }
}

[pscustomobject]@{ status = 'passed'; assertions = $assertions; liveDocker = 'verified'; resources = 'retained' } |
    ConvertTo-Json -Compress

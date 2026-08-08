[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot '_devops-common.ps1')

function Assert-LifecycleTest {
    param([bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

$generation = New-DevServiceGeneration
$generationPath = [IO.Path]::GetFullPath($generation.LogDirectory)
try {
    Assert-LifecycleTest (Test-Path -LiteralPath $generationPath -PathType Container) 'service generation was not created'
    Remove-DevServiceGenerationIfUnpublished -Generation $generation
    Assert-LifecycleTest (-not (Test-Path -LiteralPath $generationPath)) 'unpublished generation was not removed'
} finally {
    if (Test-Path -LiteralPath $generationPath -PathType Container) { Remove-Item -LiteralPath $generationPath -Recurse -Force }
}

$outside = [pscustomobject]@{LogDirectory = Join-Path ([IO.Path]::GetTempPath()) 'mms-invalid-generation'}
$outsideRejected = $false
try { Remove-DevServiceGenerationIfUnpublished -Generation $outside } catch { $outsideRejected = $true }
Assert-LifecycleTest $outsideRejected 'generation cleanup accepted a path outside scripts/.logs/generations'

$startText = Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-start.ps1') -Raw -Encoding UTF8
$lineFailure = $startText.IndexOf('if (-not $lineWebhookReady) { exit 1 }', [StringComparison]::Ordinal)
$stateWrite = $startText.IndexOf('$stateUpdates = @{', [StringComparison]::Ordinal)
$durableStateWrite = $startText.IndexOf('Write-DevState -Updates $stateUpdates', [StringComparison]::Ordinal)
Assert-LifecycleTest ($lineFailure -ge 0 -and $stateWrite -gt $lineFailure) 'dev-start writes healthy state before final LINE failure gate'
Assert-LifecycleTest ($startText.Contains('Remove-DevServiceGenerationIfUnpublished -Generation $script:StartServiceGeneration')) 'dev-start lacks unpublished generation rollback'
Assert-LifecycleTest ($startText.Contains('$script:StartServiceGenerationPublished = $false')) 'dev-start lacks unpublished generation default'
Assert-LifecycleTest ($startText.Contains('$previousState.PSObject.Properties[''dispatcherPid'']')) 'dev-start does not handle an absent Dispatcher state field under strict mode'
Assert-LifecycleTest ($durableStateWrite -gt $stateWrite -and $startText.IndexOf('Publish-EnvironmentManagedOperationReceipt -Capability DEV_RUNTIME',[StringComparison]::Ordinal) -gt $durableStateWrite) 'dev-start publishes runtime participation before durable generation state'
Assert-LifecycleTest ($startText.Contains('Publish-EnvironmentManagedOperationReceipt -Capability LINE_E2E')) 'dev-start does not publish successful LINE operation participation'
$statusText=Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-status.ps1') -Raw -Encoding UTF8
Assert-LifecycleTest ($statusText.Contains('Publish-EnvironmentManagedOperationReceipt -Capability LINE_E2E')) 'explicit LINE status probe does not publish managed operation participation'

foreach ($name in @('dev-start.ps1','dev-restart.ps1','dev-stop.ps1')) {
    $text = Get-Content -LiteralPath (Join-Path $scriptsRoot $name) -Raw -Encoding UTF8
    Assert-LifecycleTest ($text -notmatch '(?im)^\s*(?:docker\s+)?compose\s+(?:up|down|stop)\b') "$name contains direct Compose lifecycle mutation"
}
$restartText = Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-restart.ps1') -Raw -Encoding UTF8
Assert-LifecycleTest ($restartText.Contains('Shared persistent Docker infrastructure is preserved')) 'full restart does not preserve shared infrastructure policy'
Assert-LifecycleTest ($restartText -notmatch '(?im)dev-stop\.ps1[^\r\n]*-Docker') 'full restart forwards a Docker stop mutation'
Assert-LifecycleTest ($restartText.Contains('$startParameters["NoNgrok"] = $true')) 'restart cannot preserve a local-only NoNgrok decision'
Assert-LifecycleTest ($restartText.Contains('Get-SafeChildExitCode')) 'restart reads an unset LASTEXITCODE under strict mode'

[pscustomobject]@{status='passed';assertions=16;generationRollback='verified';sharedDockerLifecycle='no direct compose mutation'} | ConvertTo-Json -Compress

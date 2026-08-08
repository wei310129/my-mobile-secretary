[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scriptsRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot '_devops-common.ps1')
$assertions=0
function Assert-DevStartReceipt([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:assertions++}

$published=[Collections.Generic.List[object]]::new()
$publisher={
    param($Capability,$Operation,$Generation)
    $published.Add([pscustomobject]@{Capability=$Capability;Operation=$Operation;Generation=$Generation})
}
$state=[pscustomobject]@{serviceGeneration='existing-generation'}
$shared=[pscustomobject]@{Outcome='READY';Classification='MATCH'}
Publish-DevStartManagedOperationReceipts -State $state -SharedInfrastructureResult $shared -LineReady $true -Publisher $publisher
Assert-DevStartReceipt ($published.Count -eq 3) 'reuse path did not publish Docker, runtime, and LINE receipts'
Assert-DevStartReceipt (@($published|Where-Object{$_.Capability -eq 'DOCKER_TEST' -and $_.Operation -eq 'DOCKER_SHARED_INFRASTRUCTURE_READY'}).Count -eq 1) `
    'reuse path did not publish exact Docker shared-infrastructure observation'
Assert-DevStartReceipt (@($published|Where-Object{$_.Capability -eq 'DEV_RUNTIME' -and $_.Operation -eq 'RUNTIME_START'}).Count -eq 1) `
    'reuse path did not publish runtime observation'
Assert-DevStartReceipt (@($published|Where-Object{$_.Capability -eq 'LINE_E2E' -and $_.Operation -eq 'LINE_CONNECTIVITY_PROBE'}).Count -eq 1) `
    'reuse path did not publish LINE observation'
Assert-DevStartReceipt (@($published|Where-Object{$_.Generation -ne 'existing-generation'}).Count -eq 0) `
    'reuse receipts did not use the durable existing service generation'

$missingRejected=$false
try {Publish-DevStartManagedOperationReceipts -State ([pscustomobject]@{}) -SharedInfrastructureResult $shared `
    -LineReady $false -Publisher $publisher}catch{$missingRejected=$true}
Assert-DevStartReceipt $missingRejected 'missing durable service generation was accepted'
$failurePropagated=$false
try {Publish-DevStartManagedOperationReceipts -State $state -SharedInfrastructureResult $shared -LineReady $false `
    -Publisher {param($Capability,$Operation,$Generation);throw 'receipt write failed'}}catch{$failurePropagated=$true}
Assert-DevStartReceipt $failurePropagated 'receipt publication failure was swallowed'

$startText=Get-Content -LiteralPath (Join-Path $scriptsRoot 'dev-start.ps1') -Raw -Encoding UTF8
Assert-DevStartReceipt (-not $startText.Contains('$stateUpdates.serviceGeneration')) 'dev-start still reads an optional hashtable key through StrictMode property access'
Assert-DevStartReceipt ($startText.Contains('$devStartFailure') -and $startText.Contains('exit 1')) `
    'dev-start does not explicitly convert a receipt exception into a nonzero exit'

[pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress

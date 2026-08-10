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
Assert-DevStartReceipt ($startText -match '(?s)Start-ManagedDurableProcess.*?-Component Ngrok.*?Start-ManagedDurableProcess.*?-Component SpringBoot') `
    'dev-start does not use the durable breakaway launcher for both required managed children'
Assert-DevStartReceipt ($startText.Contains('springBootOwnershipReceiptId') -and
        $startText.Contains('Assert-ManagedRuntimeReceiptCurrent')) `
    'dev-start does not persist and recheck exact Spring ownership after durable state publication'
Assert-DevStartReceipt ($startText.IndexOf('$script:StartServiceGenerationPublished = $true',[StringComparison]::Ordinal) -gt
        $startText.IndexOf('Publish-DevStartManagedOperationReceipts',[StringComparison]::Ordinal)) `
    'dev-start marks the generation successful before final managed receipts are published'

$originalStateFile=$StateFile
$fixtureRoot=Join-Path (Join-Path $scriptsRoot '.coordination-test-state') ([guid]::NewGuid().ToString())
try {
    [IO.Directory]::CreateDirectory($fixtureRoot)|Out-Null
    $StateFile=Join-Path $fixtureRoot '.dev-state.json'
    Write-CoordinationJsonAtomic -Path $StateFile -Document ([ordered]@{serviceGeneration='failed-generation';springBootPid=123})
    $previous=[pscustomobject]@{serviceGeneration='previous-generation';springBootPid=99;marker='retained'}
    Restore-DevStateAfterFailedStart -PreviousState $previous -ExpectedGeneration 'failed-generation'
    $restored=[IO.File]::ReadAllText($StateFile,[Text.Encoding]::UTF8)|ConvertFrom-Json
    Assert-DevStartReceipt ($restored.serviceGeneration -eq 'previous-generation' -and $restored.springBootPid -eq 99 -and $restored.marker -eq 'retained') `
        'failed-start state did not restore the previous durable generation'
    Write-CoordinationJsonAtomic -Path $StateFile -Document ([ordered]@{serviceGeneration='competing-generation';springBootPid=777})
    $competingRejected=$false
    try {Restore-DevStateAfterFailedStart -PreviousState $previous -ExpectedGeneration 'failed-generation'} catch {$competingRejected=$true}
    Assert-DevStartReceipt $competingRejected 'failed-start rollback overwrote a competing service generation'
} finally {
    $StateFile=$originalStateFile
    if(Test-Path -LiteralPath $fixtureRoot){Remove-Item -LiteralPath $fixtureRoot -Recurse -Force}
}

[pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress

[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scriptsRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')
$repoRoot=Split-Path -Parent $scriptsRoot
$testBase=Join-Path $scriptsRoot '.environment-test-state'
$testRoot=Join-Path $testBase ([guid]::NewGuid().ToString())
$evidencePath=Join-Path $repoRoot ("docs\exec-plans\evidence\development-environment\test-fake-$([guid]::NewGuid().ToString('n')).json")
[IO.Directory]::CreateDirectory($testRoot)|Out-Null
try{
    Write-EnvironmentIssue -Code 'EVOLVED_FAKE' -Capability READ_ONLY -Expected ready -Actual blocked -RecheckKind CAPABILITY `
        -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop|Out-Null
    $context=Get-EnvironmentStateContext -RepoRoot $repoRoot -StateRoot $testRoot -MachineAlias test-laptop
    $otherCaller=if((Get-EnvironmentCallerContext).Kind -eq 'host'){'sandbox'}else{'host'}
    $readyProbe=[pscustomobject]@{State='MATCH';Ready=$true;Reason=$null;Version='fake'}
    $crossSnapshot=New-EnvironmentSnapshot -Capability READ_ONLY -RepoRoot $repoRoot -StateRoot $testRoot `
        -MachineAlias test-laptop -ProbeOverrides @{PowerShell=$readyProbe;Git=$readyProbe} -NoWrite
    $crossSnapshot.caller.Kind=$otherCaller
    Write-CoordinationJsonAtomic -Path (Get-EnvironmentSnapshotPath -Context $context -Capability READ_ONLY -CallerKind $otherCaller) -Document $crossSnapshot
    $contract=Get-EnvironmentContract -RepoRoot $repoRoot
    $crossFingerprint=Get-EnvironmentIssueFingerprint -Code 'CROSS_CALLER_FAKE' -Capability READ_ONLY -CallerKind $otherCaller
    Write-CoordinationJsonAtomic -Path (Join-Path $context.IssuesPath "$crossFingerprint.json") -Document ([ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;fingerprint=$crossFingerprint;code='CROSS_CALLER_FAKE';capability='READ_ONLY'
        callerKind=$otherCaller;firstSeen=[datetime]::UtcNow.ToString('o');lastSeen=[datetime]::UtcNow.ToString('o')
        occurrences=1;expected='ready';actual='blocked';contractFingerprint=$contract.Fingerprint
        recheckKind='CAPABILITY';status='OPEN';resolutionEvidence=$null
    })
    $output=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptsRoot 'dev-environment-review.ps1') `
        -ReleaseGate fake-gate -Mode Automatic -Capability READ_ONLY -TargetWorktree $repoRoot `
        -EvidencePath $evidencePath -StateRoot $testRoot -MachineAlias test-laptop -Json
    if($LASTEXITCODE -ne 0){throw "automatic fake review failed with exit $LASTEXITCODE"}
    $review=($output -join "`n")|ConvertFrom-Json
    if($review.outcome -ne 'PASS'){throw 'ready capability did not produce PASS review'}
    if(@($review.issues|Where-Object{$_.code -eq 'EVOLVED_FAKE' -and $_.classification -eq 'FIXED'}).Count -ne 1){throw 'resolved issue was not classified FIXED'}
    if(@($review.issues|Where-Object{$_.code -eq 'CROSS_CALLER_FAKE' -and $_.classification -eq 'FIXED'}).Count -ne 1){throw 'matching cross-caller snapshot was not rechecked'}
    if(-not(Test-Path -LiteralPath (Join-Path $context.ReviewsPath 'fake-gate.json'))){throw 'review receipt was not durable'}
    $validation=Assert-EnvironmentReviewDocument -Review $review -ReleaseGate fake-gate -Mode Automatic -RepoRoot $repoRoot `
        -TargetWorktree $repoRoot -AnchorWorktree $repoRoot -EvidenceRoot (Split-Path -Parent $evidencePath) `
        -MachineAlias test-laptop -RequiredCapability READ_ONLY
    if($validation.status -ne 'passed'){throw 'review document validator rejected valid evidence'}
    $aliasRejected=$false
    try{Assert-EnvironmentReviewDocument -Review $review -ReleaseGate fake-gate -Mode Automatic -RepoRoot $repoRoot -MachineAlias test-desktop|Out-Null}catch{$aliasRejected=$true}
    if(-not $aliasRejected){throw 'review document validator accepted evidence from another machine alias'}
    $capabilityRejected=$false
    try{Assert-EnvironmentReviewDocument -Review $review -ReleaseGate fake-gate -Mode Automatic -RepoRoot $repoRoot -RequiredCapability MAVEN|Out-Null}catch{$capabilityRejected=$true}
    if(-not $capabilityRejected){throw 'review document validator accepted missing required capability'}
    $review.contractFingerprint='stale'
    $staleRejected=$false
    try{Assert-EnvironmentReviewDocument -Review $review -ReleaseGate fake-gate -Mode Automatic -RepoRoot $repoRoot|Out-Null}catch{$staleRejected=$true}
    if(-not $staleRejected){throw 'review document validator accepted stale contract evidence'}
    [pscustomobject]@{status='passed';assertions=8;livePaths='skipped'}|ConvertTo-Json -Compress
}finally{
    if(Test-Path -LiteralPath $testRoot){Remove-Item -LiteralPath $testRoot -Recurse -Force}
    if(Test-Path -LiteralPath $evidencePath){Remove-Item -LiteralPath $evidencePath -Force}
}

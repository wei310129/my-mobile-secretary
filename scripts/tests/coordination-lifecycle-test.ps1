
[CmdletBinding()]
param([ValidateSet('Fake')][string]$Adapter = 'Fake')
$ErrorActionPreference='Stop'; $script=Join-Path (Split-Path -Parent $PSScriptRoot) 'coordination-lifecycle.ps1'; . $script
$root=Join-Path (Join-Path (Split-Path -Parent $PSScriptRoot) '.coordination-test-state') ([guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($root)|Out-Null
function Assert-Life([bool]$ok,[string]$message){if(-not $ok){throw $message}}
try {
 Assert-Life ((Invoke-DispatcherDrainAdapter -Action Drain).Outcome -eq 'BLOCKED') 'disabled live drain adapter made a request'
 Assert-Life ((Invoke-DispatcherDrainAdapter -Action Drain -EnableLiveAdapter).Outcome -eq 'BLOCKED') 'incomplete live drain configuration did not fail closed'
 Assert-Life ((Resolve-CoordinationCleanupClass -Labels @{ 'com.mms.coordination.class' = 'shared-persistent' }) -eq 'kept-persistent') 'shared persistent Compose label was not retained'
 $ready=Join-Path $root 'ready'; $job=Start-Job -ScriptBlock {param($path,$state,$ready);. $path;Invoke-CoordinatedLifecycleFake -StateRoot $state -Action Restart -DelayMilliseconds 5000 -ReadyPath $ready|ConvertTo-Json -Compress} -ArgumentList $script,$root,$ready
 $deadline=[datetime]::UtcNow.AddSeconds(30);while(-not(Test-Path $ready)-and [datetime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 50};Assert-Life (Test-Path $ready) 'transition owner did not reserve'
 $loser=Invoke-CoordinatedLifecycleFake -StateRoot $root -Action Start -TimeoutSeconds 1
 Assert-Life ($loser.Outcome -eq 'BUSY') 'transition interleaved'
 $job|Wait-Job|Out-Null; $winner=Receive-Job $job -ErrorAction Stop|ConvertFrom-Json; Assert-Life ($winner.Outcome -eq 'READY') 'transition owner failed'
 Assert-Life ((Invoke-CoordinatedLifecycleFake -StateRoot $root -Action Stop -DispatcherState Unknown).Outcome -eq 'BLOCKED') 'unknown Dispatcher was not blocked'
 Assert-Life ((Invoke-CoordinatedLifecycleFake -StateRoot $root -Action DockerRepair -DockerConsumer).Outcome -eq 'BUSY') 'Docker repair ignored consumer'
 Assert-Life ((Invoke-CoordinatedLifecycleFake -StateRoot $root -Action Stop -StopVerificationFails).Outcome -eq 'RECOVERY_REQUIRED') 'failed stop cleared ownership'
 [pscustomobject]@{status='passed';assertions=8;adapter=$Adapter;livePaths='skipped'}|ConvertTo-Json -Compress
} finally {if($job){Remove-Job $job -Force -ErrorAction SilentlyContinue};if(Test-Path $root){Remove-Item $root -Recurse -Force}}


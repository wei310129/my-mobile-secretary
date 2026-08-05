[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$adapter = Join-Path (Split-Path -Parent $PSScriptRoot) 'coordination-maven.ps1'
. $adapter
$base = Join-Path ([IO.Path]::GetTempPath()) 'mms-coordination-test'
$root = Join-Path $base ([guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($root) | Out-Null
$jobs = [Collections.Generic.List[object]]::new()
function Assert-Maven([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
try {
    $disabled = Invoke-CoordinatedMavenFake -Application root -Worktree $root -StateRoot $root
    Assert-Maven ($disabled.Outcome -eq 'BLOCKED') 'disabled adapter changed runtime behavior'
    $operations = @('Build','Test','Clean','SpotlessApply','SpringBootRun','Build','Test','Clean')
    foreach ($operation in $operations) {
        $jobs.Add((Start-Job -ScriptBlock { param($path,$state,$operation); . $path; Invoke-CoordinatedMavenFake -Application root -Worktree $state -StateRoot $state -Operation $operation -DelayMilliseconds 50 -EnableFakeAdapter | ConvertTo-Json -Compress } -ArgumentList $adapter,$root,$operation))
    }
    $jobs | Wait-Job | Out-Null
    $results = @($jobs | Receive-Job -ErrorAction Stop | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-Maven (@($results | Where-Object Outcome -ne 'READY').Count -eq 0) 'root target writer contention failed'
    $artifacts = @($results | Select-Object -ExpandProperty Artifact)
    Assert-Maven (($artifacts | Select-Object -Unique).Count -eq 8 -and @($artifacts | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -eq 0) 'immutable root artifacts were overwritten'
    $jobs.Clear()
    $dispatcher = Invoke-CoordinatedMavenFake -Application dispatcher -Worktree $root -StateRoot $root -Operation Build -EnableFakeAdapter
    Assert-Maven ($dispatcher.Outcome -eq 'READY' -and (Test-Path -LiteralPath $dispatcher.Artifact)) 'dispatcher target/artifact adapter failed'
    $runReady = Join-Path $root 'run-ready.txt'
    $run = Start-Job -ScriptBlock { param($path,$state,$ready); . $path; Invoke-CoordinatedMavenFake -Application root -Worktree "$state\run" -StateRoot $state -Operation SpringBootRun -DelayMilliseconds 1200 -EnableFakeAdapter -ReadyPath $ready | ConvertTo-Json -Compress } -ArgumentList $adapter,$root,$runReady
    $deadline = [datetime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $runReady) -and [datetime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    Assert-Maven (Test-Path -LiteralPath $runReady) 'fake SpringBootRun did not acquire its target lease'
    $loser = Invoke-CoordinatedMavenFake -Application root -Worktree "$root\run" -StateRoot $root -Operation Test -TimeoutSeconds 1 -EnableFakeAdapter
    Assert-Maven ($loser.Outcome -eq 'BUSY') 'running service did not hold its target lease'
    $run | Wait-Job | Out-Null; $runResult = Receive-Job -Job $run -ErrorAction Stop | ConvertFrom-Json
    Assert-Maven ($runResult.Outcome -eq 'READY') 'fake SpringBootRun failed'
    $residual = Invoke-CoordinatedMavenFake -Application root -Worktree "$root\residual" -StateRoot $root -Operation Build -EnableFakeAdapter -InjectChildResidual
    Assert-Maven ($residual.Outcome -eq 'RECOVERY_REQUIRED') 'injected child residual did not require recovery'
    $blocked = Invoke-CoordinatedMavenFake -Application root -Worktree "$root\residual" -StateRoot $root -Operation Build -EnableFakeAdapter
    Assert-Maven ($blocked.Outcome -eq 'RECOVERY_REQUIRED') 'new writer ignored child residual'
    $liveReady = Join-Path $root 'live-adapter-ready.txt'
    $live = Start-Job -ScriptBlock {
        param($path,$state,$worktree,$ready)
        . $path
        Invoke-CoordinatedMavenOperation -Application root -Worktree $worktree -StateRoot $state -Operation Build -Runner {
            [IO.File]::WriteAllText($ready, 'ready', [Text.UTF8Encoding]::new($false))
            Start-Sleep -Milliseconds 1200
            0
        } | ConvertTo-Json -Compress
    } -ArgumentList $adapter,$root,"$root\live-adapter",$liveReady
    $deadline = [datetime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $liveReady) -and [datetime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    Assert-Maven (Test-Path -LiteralPath $liveReady) 'production Maven adapter did not acquire its target lease'
    $contender = Invoke-CoordinatedMavenOperation -Application root -Worktree "$root\live-adapter" -StateRoot $root -Operation Test -TimeoutSeconds 1 -Runner { 0 }
    Assert-Maven ($contender.Outcome -eq 'BUSY') 'production Maven adapter allowed concurrent target writer'
    $live | Wait-Job | Out-Null
    $liveResult = Receive-Job -Job $live -ErrorAction Stop | ConvertFrom-Json
    Remove-Job -Job $live -Force
    Assert-Maven ($liveResult.Outcome -eq 'READY') 'production Maven adapter runner failed'
    $failure = Invoke-CoordinatedMavenOperation -Application root -Worktree "$root\failure-adapter" -StateRoot $root -Operation Test -Runner { 7 }
    Assert-Maven ($failure.Outcome -eq 'FAILED' -and $failure.ExitCode -eq 7) 'production Maven adapter did not retain runner failure outcome'
    $sourceReady = Join-Path $root 'source-writer-ready.txt'
    $sourceJob = Start-Job -ScriptBlock {
        param($path,$worktree,$ready)
        . $path
        $writer = Enter-CoordinationSourceWriter -Worktree $worktree
        try {
            [IO.File]::WriteAllText($ready, 'ready', [Text.UTF8Encoding]::new($false))
            Start-Sleep -Milliseconds 1200
        } finally { Exit-CoordinationOperation $writer }
    } -ArgumentList $adapter,"$root\spotless",$sourceReady
    $deadline = [datetime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $sourceReady) -and [datetime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    Assert-Maven (Test-Path -LiteralPath $sourceReady) 'source-write competitor did not acquire its claim'
    $spotless = Invoke-CoordinatedMavenOperation -Application root -Worktree "$root\spotless" -StateRoot $root -Operation SpotlessApply -SourceWrite -TimeoutSeconds 1 -Runner { 0 }
    Assert-Maven ($spotless.Outcome -eq 'BUSY') 'Spotless adapter bypassed an active source-write claim'
    $sourceJob | Wait-Job | Out-Null
    Receive-Job -Job $sourceJob -ErrorAction Stop | Out-Null
    Remove-Job -Job $sourceJob -Force
    [pscustomobject]@{ status='passed'; assertions=13; rootArtifacts=$artifacts.Count; dispatcherArtifact=$true; livePaths='skipped' } | ConvertTo-Json -Compress
} finally {
    foreach ($job in @($jobs)) { if ($job.State -eq 'Running') { Stop-Job $job -ErrorAction SilentlyContinue }; Remove-Job $job -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

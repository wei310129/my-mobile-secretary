[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$common = Join-Path (Split-Path -Parent $PSScriptRoot) 'coordination-common.ps1'
. $common
$testBase = Join-Path (Split-Path -Parent $PSScriptRoot) '.coordination-test-state'
$testRoot = Join-Path $testBase ([guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$jobs = [Collections.Generic.List[object]]::new()

function Assert-Kernel {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

try {
    # 20 independent PowerShell processes must never overlap one exclusive critical section.
    $marker = Join-Path $testRoot 'exclusive-events.log'
    1..20 | ForEach-Object {
        $jobs.Add((Start-Job -ScriptBlock {
            param($commonPath, $eventPath, $id)
            . $commonPath
            $resource = New-CoordinationResource -Type 'worktree/maven-target' -Key 'kernel-exclusive' -Mode Exclusive
            $operation = Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds 20
            if ($operation.Outcome -ne 'READY') { throw 'exclusive worker unexpectedly BUSY' }
            try {
                [IO.File]::AppendAllText($eventPath, "E|$id`n")
                Start-Sleep -Milliseconds 60
                [IO.File]::AppendAllText($eventPath, "X|$id`n")
            } finally { Exit-CoordinationOperation $operation }
        } -ArgumentList $common, $marker, $_))
    }
    $jobs | Wait-Job | Out-Null
    $jobs | Receive-Job -ErrorAction Stop | Out-Null
    $events = @(Get-Content -LiteralPath $marker -Encoding UTF8)
    $inside = 0; $maximum = 0
    foreach ($event in $events) { if ($event.StartsWith('E|')) { $inside++ } else { $inside-- }; $maximum = [Math]::Max($maximum, $inside) }
    Assert-Kernel ($events.Count -eq 40 -and $maximum -eq 1 -and $inside -eq 0) 'exclusive concurrency oracle failed'
    $jobs.Clear()

    # Machine locks ignore clone/worktree path; repo metadata shares only a Git common-dir.
    $commonDir = Join-Path $testRoot 'clone-a\.git'
    $sameCloneWorktree = Get-CoordinationRepoRoot -GitCommonDir $commonDir
    $sameCloneMain = Get-CoordinationRepoRoot -GitCommonDir $commonDir
    $otherClone = Get-CoordinationRepoRoot -GitCommonDir (Join-Path $testRoot 'clone-b\.git')
    Assert-Kernel ($sameCloneWorktree -eq $sameCloneMain) 'repo-common root did not share a Git common-dir'
    Assert-Kernel ($sameCloneWorktree -ne $otherClone) 'repo-common root incorrectly crossed clone boundaries'

    # Caller order is irrelevant: canonical sorting prevents a multi-key deadlock.
    1..8 | ForEach-Object {
        $reverse = ($_ % 2 -eq 0)
        $jobs.Add((Start-Job -ScriptBlock {
            param($commonPath, $reverseOrder)
            . $commonPath
            $first = New-CoordinationResource -Type 'worktree/source' -Key 'multi-a' -Mode Exclusive
            $second = New-CoordinationResource -Type 'environment' -Key 'multi-b' -Mode Exclusive
            $resources = if ($reverseOrder) { @($second, $first) } else { @($first, $second) }
            $operation = Enter-CoordinationOperation -Resources $resources -TimeoutSeconds 20
            if ($operation.Outcome -ne 'READY') { throw 'multi-key worker unexpectedly BUSY' }
            try { Start-Sleep -Milliseconds 30 } finally { Exit-CoordinationOperation $operation }
        } -ArgumentList $common, $reverse))
    }
    $jobs | Wait-Job | Out-Null
    $jobs | Receive-Job -ErrorAction Stop | Out-Null
    $jobs.Clear()

    # A timeout returns BUSY and releases every partial lease before any external mutation.
    $alpha = New-CoordinationResource -Type 'worktree/maven-target' -Key 'timeout-a' -Mode Exclusive
    $beta = New-CoordinationResource -Type 'environment' -Key 'timeout-b' -Mode Exclusive
    $owner = Enter-CoordinationOperation -Resources @($alpha) -TimeoutSeconds 5
    Assert-Kernel ($owner.Outcome -eq 'READY') 'timeout owner did not acquire alpha'
    try {
        $busyJob = Start-Job -ScriptBlock {
            param($commonPath)
            . $commonPath
            $a = New-CoordinationResource -Type 'worktree/maven-target' -Key 'timeout-a' -Mode Exclusive
            $b = New-CoordinationResource -Type 'environment' -Key 'timeout-b' -Mode Exclusive
            (Enter-CoordinationOperation -Resources @($b, $a) -TimeoutSeconds 1).Outcome
        } -ArgumentList $common
        $jobs.Add($busyJob); $busyJob | Wait-Job | Out-Null
        $busyOutcome = Receive-Job -Job $busyJob -ErrorAction Stop
        Assert-Kernel ($busyOutcome -eq 'BUSY') 'timeout did not return BUSY'
    } finally { Exit-CoordinationOperation $owner }
    $betaAfterTimeout = Enter-CoordinationOperation -Resources @($beta) -TimeoutSeconds 2
    Assert-Kernel ($betaAfterTimeout.Outcome -eq 'READY') 'partial multi-key lease was not released'
    Exit-CoordinationOperation $betaAfterTimeout
    $jobs.Clear()

    # A killed owner leaves an abandoned mutex, not a permanent lease; generation remains monotonic in registry CAS.
    $ready = Join-Path $testRoot 'crash-ready.txt'
    $escapedCommon = $common.Replace("'", "''"); $escapedReady = $ready.Replace("'", "''"); $escapedRoot = $testRoot.Replace("'", "''")
    $crashSource = ". '$escapedCommon'; `$r = New-CoordinationResource -Type 'worktree/maven-target' -Key 'crash-owner' -Mode Exclusive; `$o = Enter-CoordinationOperation -Resources @(`$r) -TimeoutSeconds 10 -StateRoot '$escapedRoot'; if (`$o.Outcome -ne 'READY') { exit 2 }; [IO.File]::WriteAllText('$escapedReady', `$o.OperationId); Start-Sleep -Seconds 60"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($crashSource))
    $crashed = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded -PassThru -WindowStyle Hidden
    $deadline = [datetime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $ready) -and [datetime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50 }
    Assert-Kernel (Test-Path -LiteralPath $ready) 'crash owner did not acquire its lease'
    Stop-Process -Id $crashed.Id -Force
    $crashed.WaitForExit()
    $crashOperationId = [IO.File]::ReadAllText($ready, [Text.Encoding]::UTF8)
    $readOnlyCrashManifest = Get-CoordinationOperationManifest -StateRoot $testRoot -OperationId $crashOperationId
    Assert-Kernel ($readOnlyCrashManifest.status -eq 'ACTIVE') 'read-only operation lookup mutated a stale manifest'
    $crashResource = (New-CoordinationResource -Type 'worktree/maven-target' -Key 'crash-owner' -Mode Exclusive).CanonicalKey
    $crashClassification = Get-CoordinationOperationOwnerClassification -Manifest $readOnlyCrashManifest -ExpectedResource $crashResource
    Assert-Kernel ($crashClassification.State -eq 'STALE_DEAD') 'dead operation owner was not typed as stale'
    $reconcile = Resolve-CoordinationStaleOperation -StateRoot $testRoot -OperationId $crashOperationId -ExpectedResource $crashResource
    $abandoned = Get-CoordinationOperationManifest -StateRoot $testRoot -OperationId $crashOperationId
    Assert-Kernel ($reconcile.Changed -and $abandoned.status -eq 'ABANDONED' -and
            $abandoned.reconcileEvidence.schema -eq 'MMS_COORDINATION_RECONCILE_V1') 'formal reconcile did not retain typed abandonment evidence'
    $replay = Resolve-CoordinationStaleOperation -StateRoot $testRoot -OperationId $crashOperationId -ExpectedResource $crashResource
    Assert-Kernel ($replay.Outcome -eq 'READY' -and -not $replay.Changed) 'stale operation reconcile was not exactly-once'
    $recovered = Enter-CoordinationOperation -Resources @(New-CoordinationResource -Type 'worktree/maven-target' -Key 'crash-owner' -Mode Exclusive) -TimeoutSeconds 5
    Assert-Kernel ($recovered.Outcome -eq 'READY') 'abandoned owner lock did not release'
    Exit-CoordinationOperation $recovered

    $registryName = 'parallel-cas'
    Write-CoordinationJsonAtomic -Path (Get-CoordinationRegistryPath $testRoot $registryName) -Document ([ordered]@{ generation = 0; value = 0 })
    1..20 | ForEach-Object {
        $jobs.Add((Start-Job -ScriptBlock {
            param($commonPath, $root, $name)
            . $commonPath
            1..10 | ForEach-Object {
                do {
                    $current = Read-CoordinationRegistry -StateRoot $root -Name $name
                    $next = [ordered]@{ generation = 0; value = ([int]$current.value + 1) }
                    $result = Invoke-CoordinationRegistryCas -StateRoot $root -Name $name -ExpectedGeneration ([long]$current.generation) -NextDocument $next
                } while (-not $result.Applied)
            }
        } -ArgumentList $common, $testRoot, $registryName))
    }
    $jobs | Wait-Job | Out-Null
    $jobs | Receive-Job -ErrorAction Stop | Out-Null
    $final = Read-CoordinationRegistry -StateRoot $testRoot -Name $registryName
    Assert-Kernel ([long]$final.generation -eq 200 -and [int]$final.value -eq 200) 'atomic CAS lost an update or regressed generation'
    $jobs.Clear()

    $logDirectory = New-CoordinationServiceLogDirectory -StateRoot $testRoot -EnvironmentId 'fake-dev' -Generation 7
    $logFile = Join-Path $logDirectory 'service.log'; [IO.File]::WriteAllText($logFile, 'active-generation')
    Write-CoordinationReceipt -StateRoot $testRoot -Receipt ([ordered]@{ operationId = 'receipt-kernel'; outcome = 'READY'; disposition = 'kept-shared' })
    Assert-Kernel ((Test-Path -LiteralPath $logFile) -and (Test-Path -LiteralPath (Join-Path $testRoot 'receipts\receipt-kernel.json'))) 'receipt overwrote an active service generation log'
    $nextLogDirectory = New-CoordinationServiceLogDirectory -StateRoot $testRoot -EnvironmentId 'fake-dev' -Generation 8
    Assert-Kernel ($logDirectory -ne $nextLogDirectory) 'distinct service generations shared a log directory'
    $doctor = Get-CoordinationDoctorSnapshot -StateRoot $testRoot
    Assert-Kernel ($doctor.externalProbe -eq 'skipped' -and $doctor.cleanup -like 'read-only*') 'doctor attempted a mutation or external probe'

    [pscustomobject]@{ status = 'passed'; assertions = 15; processes = 20; maximumCriticalSection = $maximum; casGeneration = $final.generation; livePaths = 'skipped' } | ConvertTo-Json -Compress
} finally {
    foreach ($job in @($jobs)) { if ($job.State -eq 'Running') { Stop-Job -Job $job -ErrorAction SilentlyContinue }; Remove-Job -Job $job -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}

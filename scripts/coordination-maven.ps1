Set-StrictMode -Version Latest
. "$PSScriptRoot\coordination-common.ps1"

function Invoke-CoordinatedMavenOperation {
    param(
        [Parameter(Mandatory)][ValidateSet('root', 'dispatcher')][string]$Application,
        [Parameter(Mandatory)][string]$Worktree,
        [ValidateSet('Build', 'Test', 'Clean', 'SpotlessApply', 'SpringBootRun')][string]$Operation = 'Build',
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 300,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [switch]$SourceWrite,
        [Parameter(Mandatory)][scriptblock]$Runner
    )
    $normalizedWorktree = [IO.Path]::GetFullPath($Worktree)
    $resources = @(
        (New-CoordinationResource -Type 'worktree/maven-target' -Key "$Application/$normalizedWorktree" -Mode Exclusive)
    )
    if ($SourceWrite) {
        $resources += New-CoordinationResource -Type 'worktree/source' -Key $normalizedWorktree -Mode Exclusive
    }
    $lease = Enter-CoordinationOperation -Resources $resources -TimeoutSeconds $TimeoutSeconds -StateRoot $StateRoot
    if ($lease.Outcome -ne 'READY') { return $lease }
    try {
        $exitCode = [int](& $Runner)
        $outcome = if ($exitCode -eq 0) { 'READY' } else { 'FAILED' }
        Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
            operationId = $lease.OperationId; outcome = $outcome; application = $Application; operation = $Operation
            targetKey = "$Application/$normalizedWorktree"; disposition = 'kept-shared'; exitCode = $exitCode
        })
        return [pscustomobject]@{ Outcome = $outcome; ExitCode = $exitCode; OperationId = $lease.OperationId }
    } finally {
        Exit-CoordinationOperation $lease
    }
}

function Invoke-CoordinatedMavenFake {
    param(
        [Parameter(Mandatory)][ValidateSet('root', 'dispatcher')][string]$Application,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][string]$StateRoot,
        [ValidateSet('Build', 'Test', 'Clean', 'SpotlessApply', 'SpringBootRun')][string]$Operation = 'Build',
        [ValidateRange(0, 5000)][int]$DelayMilliseconds = 0,
        [ValidateRange(1, 60)][int]$TimeoutSeconds = 10,
        [switch]$EnableFakeAdapter,
        [switch]$InjectChildResidual,
        [string]$ReadyPath
    )
    if (-not $EnableFakeAdapter) { return [pscustomobject]@{ Outcome = 'BLOCKED'; Reason = 'Phase 2 adapter is disabled outside fake validation.' } }
    $targetKey = "$Application/$([IO.Path]::GetFullPath($Worktree).ToLowerInvariant())"
    $target = Join-Path $StateRoot "targets\$(Get-CoordinationHash $targetKey)"
    $residual = Join-Path $target 'child-residual.json'
    if (Test-Path -LiteralPath $residual -PathType Leaf) { return [pscustomobject]@{ Outcome = 'RECOVERY_REQUIRED'; Reason = 'owned child residual requires verified recovery; clean is not automatic.' } }
    $resource = New-CoordinationResource -Type 'worktree/maven-target' -Key $targetKey -Mode Exclusive
    $lease = Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds $TimeoutSeconds -StateRoot $StateRoot
    if ($lease.Outcome -ne 'READY') { return $lease }
    try {
        [IO.Directory]::CreateDirectory($target) | Out-Null
        $generation = [guid]::NewGuid().ToString('n')
        if ($ReadyPath) { [IO.File]::WriteAllText($ReadyPath, $lease.OperationId, [Text.UTF8Encoding]::new($false)) }
        if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }
        if ($InjectChildResidual) {
            Write-CoordinationJsonAtomic -Path $residual -Document ([ordered]@{ operationId = $lease.OperationId; generation = $generation; disposition = 'unknown' })
            return [pscustomobject]@{ Outcome = 'RECOVERY_REQUIRED'; OperationId = $lease.OperationId; Reason = 'fake child residual injected' }
        }
        $artifact = Join-Path (Join-Path (Join-Path $StateRoot 'artifacts') $Application) $generation
        [IO.Directory]::CreateDirectory($artifact) | Out-Null
        $artifactFile = Join-Path $artifact 'application.jar'
        [IO.File]::WriteAllText($artifactFile, "$Application|$Operation|$generation", [Text.UTF8Encoding]::new($false))
        Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
            operationId = $lease.OperationId; outcome = 'READY'; application = $Application; operation = $Operation
            targetKey = $targetKey; immutableArtifact = $artifactFile; disposition = 'kept-shared'
        })
        return [pscustomobject]@{ Outcome = 'READY'; OperationId = $lease.OperationId; Artifact = $artifactFile }
    } finally { Exit-CoordinationOperation $lease }
}

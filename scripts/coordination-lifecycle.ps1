Set-StrictMode -Version Latest
. "$PSScriptRoot\coordination-common.ps1"

function Invoke-DispatcherDrainAdapter {
    param(
        [ValidateSet('Drain', 'Resume')][string]$Action,
        [string]$BaseUrl,
        [string]$AdminToken,
        [string]$Actor,
        [switch]$EnableLiveAdapter
    )
    if (-not $EnableLiveAdapter) {
        return [pscustomobject]@{ Outcome = 'BLOCKED'; Reason = 'Dispatcher drain adapter is disabled until an explicit live approval.' }
    }
    if ([string]::IsNullOrWhiteSpace($BaseUrl) -or [string]::IsNullOrWhiteSpace($AdminToken) -or [string]::IsNullOrWhiteSpace($Actor)) {
        return [pscustomobject]@{ Outcome = 'BLOCKED'; Reason = 'Dispatcher drain requires base URL, dedicated admin token, and actor.' }
    }
    $method = if ($Action -eq 'Drain') { 'Post' } else { 'Delete' }
    try {
        $response = Invoke-RestMethod -Method $method -Uri "$($BaseUrl.TrimEnd('/'))/internal/v1/dispatcher-drain" `
            -Headers @{ Authorization = "Bearer $AdminToken"; 'X-Dispatcher-Actor' = $Actor } -TimeoutSec 10 -ErrorAction Stop
        return [pscustomobject]@{ Outcome = 'READY'; DispatcherState = $response.state; ActiveRunId = $response.activeRunId }
    } catch {
        return [pscustomobject]@{ Outcome = 'BLOCKED'; Reason = "Dispatcher drain request could not be proven: $($_.Exception.Message)" }
    }
}

function Invoke-CoordinatedLifecycleFake {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [ValidateSet('Start','Stop','Restart','DockerRepair')][string]$Action,
        [ValidateSet('Ready','Active','Unknown')][string]$DispatcherState = 'Ready',
        [switch]$DockerConsumer,
        [switch]$StopVerificationFails,
        [ValidateRange(0,5000)][int]$DelayMilliseconds = 0,
        [ValidateRange(1,60)][int]$TimeoutSeconds = 2,
        [string]$ReadyPath
    )
    if ($DispatcherState -eq 'Unknown' -and $Action -in @('Stop','Restart')) { return [pscustomobject]@{ Outcome='BLOCKED'; Reason='Dispatcher durable drain outcome is unknown.' } }
    if ($Action -eq 'DockerRepair' -and $DockerConsumer) { return [pscustomobject]@{ Outcome='BUSY'; Reason='Docker consumer lease is active.' } }
    $resource = New-CoordinationResource -Type 'environment' -Key 'fake-dev' -Mode Exclusive
    $operation = Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds $TimeoutSeconds -StateRoot $StateRoot
    if ($operation.Outcome -ne 'READY') { return $operation }
    try {
        if ($ReadyPath) { [IO.File]::WriteAllText($ReadyPath, $operation.OperationId, [Text.UTF8Encoding]::new($false)) }
        if ($DelayMilliseconds) { Start-Sleep -Milliseconds $DelayMilliseconds }
        if ($StopVerificationFails) { return [pscustomobject]@{ Outcome='RECOVERY_REQUIRED'; Reason='PID tree or port verification failed.' } }
        Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{operationId=$operation.OperationId;outcome='READY';action=$Action;disposition='kept-shared'})
        return [pscustomobject]@{ Outcome='READY'; OperationId=$operation.OperationId }
    } finally { Exit-CoordinationOperation $operation }
}

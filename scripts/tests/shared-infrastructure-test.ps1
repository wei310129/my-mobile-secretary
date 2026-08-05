[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'coordination-common.ps1')
. (Join-Path $scriptsRoot 'docker-shared-infrastructure.ps1')

$script:FakeDockerState = @{
    Containers = @{}
    Volumes = @{}
    Calls = New-Object 'System.Collections.Generic.List[string]'
}
$script:ProbeSample = 0
$script:ReadyProbe = {
    [pscustomobject]@{
        Classification = $null; Error = $null; ContextName = 'desktop-linux'
        CliReady = $true; ContextReady = $true; DaemonReady = $true
        LifecycleReady = $true; LifecycleIdentityReady = $true; CallerAccessDenied = $false
    }
}

function Assert-SharedTest {
    param([bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

function New-FakeDockerResult {
    param([int]$ExitCode = 0, [string]$Output = '', [string]$Error = $null)
    [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output; Error = $Error }
}

function Set-FakeContainer {
    param(
        [Parameter(Mandatory)]$Contract,
        [string]$State = 'running',
        [string]$Health = 'healthy',
        [bool]$IncludeLabels = $true,
        [string]$Identity,
        [string]$Image,
        [int]$HostPort = 0,
        [string]$VolumeLogicalIdentity,
        [string]$VolumeProject
    )
    if ($HostPort -eq 0) { $HostPort = $Contract.HostPort }
    if (-not $Image) { $Image = $Contract.Image }
    if (-not $Identity) { $Identity = $Contract.CoordinationIdentity }
    if (-not $VolumeLogicalIdentity) { $VolumeLogicalIdentity = $Contract.VolumeKey }
    if (-not $VolumeProject) { $VolumeProject = $Contract.ComposeProject }
    $labels = [ordered]@{}
    if ($IncludeLabels) {
        $labels['com.mms.coordination.class'] = $Contract.CoordinationClass
        $labels['com.mms.coordination.identity'] = $Identity
        $labels['com.docker.compose.project'] = $Contract.ComposeProject
        $labels['com.docker.compose.service'] = $Contract.ComposeService
    }
    $ports = [pscustomobject]@{}
    $portKey = "$($Contract.ContainerPort)/$($Contract.Protocol)"
    $ports | Add-Member -MemberType NoteProperty -Name $portKey -Value @([pscustomobject]@{HostPort = [string]$HostPort})
    $volumeName = "$($Contract.VolumeKey)-test"
    $mount = [pscustomobject]@{
        Type = 'volume'; Name = $volumeName; Destination = $Contract.VolumeTarget; RW = $true
    }
    $container = [pscustomobject]@{
        Id = "fake-$($Contract.ContainerName)"
        Name = "/$($Contract.ContainerName)"
        Config = [pscustomobject]@{Image = $Image; Labels = [pscustomobject]$labels}
        State = [pscustomobject]@{Status = $State; Health = [pscustomobject]@{Status = $Health}}
        NetworkSettings = [pscustomobject]@{Ports = $ports}
        Mounts = @($mount)
    }
    $script:FakeDockerState.Containers[$Contract.ContainerName] = $container
    $volumeLabels = [ordered]@{
        'com.docker.compose.volume' = $VolumeLogicalIdentity
        'com.docker.compose.project' = $VolumeProject
    }
    $script:FakeDockerState.Volumes[$volumeName] = [pscustomobject]@{Name = $volumeName; Labels = [pscustomobject]$volumeLabels}
}

function Reset-FakeContainers {
    $script:FakeDockerState.Containers = @{}
    $script:FakeDockerState.Volumes = @{}
    $script:FakeDockerState.Calls = New-Object 'System.Collections.Generic.List[string]'
    foreach ($contract in @(Get-SharedInfrastructureContracts -Name @('main-postgres','main-redis'))) {
        Set-FakeContainer -Contract $contract
    }
}

function Invoke-SharedDockerCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $call = $Arguments -join '|'
    $script:FakeDockerState.Calls.Add($call)
    if ($Arguments[0] -eq 'context' -and $Arguments[1] -eq 'show') {
        return New-FakeDockerResult -Output 'desktop-linux'
    }
    if ($Arguments[0] -eq 'volume' -and $Arguments[1] -eq 'inspect') {
        $name = [string]$Arguments[$Arguments.Count - 1]
        if (-not $script:FakeDockerState.Volumes.ContainsKey($name)) { return New-FakeDockerResult -ExitCode 1 -Error 'No such object: volume' }
        return New-FakeDockerResult -Output ($script:FakeDockerState.Volumes[$name] | ConvertTo-Json -Depth 12 -Compress)
    }
    if ($Arguments[0] -eq 'inspect') {
        $name = [string]$Arguments[$Arguments.Count - 1]
        if (-not $script:FakeDockerState.Containers.ContainsKey($name)) { return New-FakeDockerResult -ExitCode 1 -Error 'No such container' }
        return New-FakeDockerResult -Output ($script:FakeDockerState.Containers[$name] | ConvertTo-Json -Depth 12 -Compress)
    }
    if ($Arguments[0] -eq 'start') {
        $name = [string]$Arguments[1]
        if (-not $script:FakeDockerState.Containers.ContainsKey($name)) { return New-FakeDockerResult -ExitCode 1 -Error 'No such container' }
        $script:FakeDockerState.Containers[$name].State.Status = 'running'
        $script:FakeDockerState.Containers[$name].State.Health.Status = 'healthy'
        return New-FakeDockerResult -Output $name
    }
    if ($Arguments[0] -eq 'compose') {
        $service = [string]$Arguments[$Arguments.Count - 1]
        $contractName = if ($Arguments -match '(?i)internal') { 'dispatcher-postgres' } else { 'main-postgres' }
        $contract = Get-SharedInfrastructureContracts -Name @($contractName) | Select-Object -First 1
        if (-not $script:FakeDockerState.Containers.ContainsKey($contract.ContainerName)) { Set-FakeContainer -Contract $contract }
        $script:FakeDockerState.Containers[$contract.ContainerName].State.Status = 'running'
        $script:FakeDockerState.Containers[$contract.ContainerName].State.Health.Status = 'healthy'
        return New-FakeDockerResult -Output $service
    }
    return New-FakeDockerResult -Output 'ok'
}

function Test-SharedHostPort {
    param([Parameter(Mandatory)][int]$Port, [int]$TimeoutSeconds = 1)
    return $true
}

function Invoke-FakeEnsure {
    param([string[]]$Names = @('main-postgres','main-redis'))
    Ensure-SharedInfrastructure -Name $Names -HealthTimeoutSeconds 3 `
        -DockerStabilityWindowSeconds 1 -DockerStabilityIntervalSeconds 0 -DockerStartupTimeoutSeconds 2 `
        -DockerProbeAdapter $script:ReadyProbe
}

$assertions = 0
try {
    Reset-FakeContainers
    $healthy = Invoke-FakeEnsure
    $assertions++
    Assert-SharedTest ($healthy.Outcome -eq 'READY' -and $healthy.Action -eq 'REUSED_HEALTHY') 'healthy shared containers were not reused'
    $assertions++
    Assert-SharedTest (@($script:FakeDockerState.Calls | Where-Object { $_ -match '(^|\|)(start|compose)(\||$)' }).Count -eq 0) 'healthy reuse performed an unexpected mutation'

    Reset-FakeContainers
    $postgres = Get-SharedInfrastructureContracts -Name @('main-postgres') | Select-Object -First 1
    Set-FakeContainer -Contract $postgres -State 'exited' -Health 'none'
    $stopped = Invoke-FakeEnsure
    $assertions++
    Assert-SharedTest ($stopped.Outcome -eq 'READY' -and $stopped.Action -eq 'STARTED_MATCHING_STOPPED') 'matching stopped container was not safely started'
    $assertions++
    Assert-SharedTest (@($script:FakeDockerState.Calls | Where-Object { $_ -match '^start\|mms-postgres$' }).Count -eq 1) 'stopped shared container was not started exactly once'
    $assertions++
    Assert-SharedTest (-not [bool]$stopped.Mutation.VolumesChanged -and @($stopped.Mutation.Removed).Count -eq 0 -and @($stopped.Mutation.Replaced).Count -eq 0) 'stopped recovery reported destructive mutation'

    $mismatchCases = @(
        [pscustomobject]@{Name='missing-labels';Args=@{IncludeLabels=$false};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'},
        [pscustomobject]@{Name='identity';Args=@{Identity='other-identity'};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'},
        [pscustomobject]@{Name='image';Args=@{Image='wrong:image'};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'},
        [pscustomobject]@{Name='port';Args=@{HostPort=5999};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'},
        [pscustomobject]@{Name='volume';Args=@{VolumeLogicalIdentity='wrong-volume'};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'},
        [pscustomobject]@{Name='volume-project';Args=@{VolumeProject='other-project'};Expected='SHARED_CONTAINER_IDENTITY_MISMATCH'}
    )
    foreach ($case in $mismatchCases) {
        Reset-FakeContainers
        $contract = Get-SharedInfrastructureContracts -Name @('main-postgres') | Select-Object -First 1
        $caseArgs = $case.Args
        Set-FakeContainer -Contract $contract @caseArgs
        $result = Invoke-FakeEnsure -Names @('main-postgres')
        $assertions++
        Assert-SharedTest ($result.Outcome -eq 'BLOCKED' -and $result.Classification -eq $case.Expected) "$($case.Name) mismatch was not fail closed"
        $assertions++
        Assert-SharedTest (@($script:FakeDockerState.Calls | Where-Object { $_ -match '(^|\|)(start|compose)(\||$)' }).Count -eq 0) "$($case.Name) mismatch caused a mutation"
    }

    $mutexName = "shared-infrastructure-test/$([guid]::NewGuid().ToString('n'))"
    $mutexOne = New-CoordinationMutex $mutexName
    $heldOne = Wait-CoordinationMutex -Mutex $mutexOne -Deadline ([datetime]::UtcNow.AddSeconds(2))
    $mutexChildName = "Global\mms-coord-v1-$(Get-CoordinationHash $mutexName)"
    $mutexJob = Start-Job -ScriptBlock {
        param([string]$Name)
        $childMutex = New-Object System.Threading.Mutex($false, $Name)
        try { $childMutex.WaitOne(300) } finally { $childMutex.Dispose() }
    } -ArgumentList $mutexChildName
    Wait-Job -Job $mutexJob -Timeout 5 | Out-Null
    $heldTwo = [bool](@(Receive-Job -Job $mutexJob -ErrorAction Stop)[0])
    Remove-Job -Job $mutexJob -Force
    if ($heldOne) { $mutexOne.ReleaseMutex() }
    $mutexOne.Dispose()
    $assertions++
    Assert-SharedTest ($heldOne -and -not $heldTwo) 'shared lifecycle mutex did not arbitrate two sessions'

    $script:ProbeSample = 0
    $unstableProbe = {
        $script:ProbeSample++
        if ($script:ProbeSample -eq 1) { & $script:ReadyProbe }
        else { [pscustomobject]@{Classification='DAEMON_NOT_READY';Error='backend exited after first sample'} }
    }
    $unstable = Test-ManagedDockerReadiness -StabilityWindowSeconds 1 -StabilityIntervalSeconds 0 -ProbeAdapter $unstableProbe
    $assertions++
    Assert-SharedTest ($unstable.Outcome -eq 'BLOCKED' -and $unstable.Classification -eq 'DAEMON_NOT_READY' -and $unstable.StableSamples -ge 2) 'instant daemon readiness was incorrectly reported as MATCH'

    $oldSandbox = $env:CODEX_SANDBOX
    try {
        $env:CODEX_SANDBOX = 'true'
        $blockedProbe = { [pscustomobject]@{Classification='DESKTOP_NOT_RUNNING';Error='desktop is down'} }
        $sandboxResult = Ensure-ManagedDockerDaemon -StabilityWindowSeconds 1 -StabilityIntervalSeconds 0 `
            -StartupTimeoutSeconds 1 -ProbeAdapter $blockedProbe
    } finally { $env:CODEX_SANDBOX = $oldSandbox }
    $assertions++
    Assert-SharedTest ($sandboxResult.Classification -eq 'AGENT_ASYNC_TOOL_REQUIRED' -and -not $sandboxResult.Started) 'sandbox detached Docker launcher did not require agent-managed monitor'

    $dockerLibrary = Get-Content -LiteralPath (Join-Path $scriptsRoot 'docker-shared-infrastructure.ps1') -Raw -Encoding UTF8
    $assertions++
    Assert-SharedTest ($dockerLibrary -notmatch '(?im)^\s*(?:&\s*)?(?:taskkill|DockerCli|Stop-Process)\b') 'Docker shared lifecycle library contains an unsafe repair or kill path'

    [pscustomobject]@{status='passed';assertions=$assertions;mutations='only fake docker state; no real container or volume touched'} | ConvertTo-Json -Compress
} finally {
    $script:FakeDockerState = $null
}

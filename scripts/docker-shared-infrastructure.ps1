Set-StrictMode -Version Latest

# Shared development containers are machine-wide persistent resources.  This library only
# starts a proven matching container or creates a missing container through the primary
# worktree Compose file.  There is intentionally no remove, replace, rename, prune, volume
# cleanup, taskkill, or Docker Desktop repair path here.

$script:SharedInfrastructureSchemaVersion = 1
$script:SharedInfrastructureContracts = @(
    [pscustomobject]@{ Name='main-postgres'; ContainerName='mms-postgres'; CoordinationClass='shared-persistent'; CoordinationIdentity='main-postgres-v1'; ComposeProject='my-mobile-secretary'; ComposeService='postgres'; ComposeFile='compose.yaml'; Image='postgis/postgis:16-3.4'; HostPort=5432; ContainerPort=5432; Protocol='tcp'; VolumeKey='postgres-data'; VolumeTarget='/var/lib/postgresql/data' },
    [pscustomobject]@{ Name='main-redis'; ContainerName='mms-redis'; CoordinationClass='shared-persistent'; CoordinationIdentity='main-redis-v1'; ComposeProject='my-mobile-secretary'; ComposeService='redis'; ComposeFile='compose.yaml'; Image='redis:7-alpine'; HostPort=6379; ContainerPort=6379; Protocol='tcp'; VolumeKey='redis-data'; VolumeTarget='/data' },
    [pscustomobject]@{ Name='dispatcher-postgres'; ContainerName='mms-ai-dispatcher-postgres'; CoordinationClass='shared-persistent'; CoordinationIdentity='dispatcher-postgres-v1'; ComposeProject='mms-ai-dispatcher'; ComposeService='postgres'; ComposeFile='internal\ai-dispatcher\compose.yaml'; Image='postgres:16-alpine'; HostPort=5433; ContainerPort=5432; Protocol='tcp'; VolumeKey='ai-dispatcher-postgres-data'; VolumeTarget='/var/lib/postgresql/data' }
)
$script:DockerDesktopExecutableName = 'Docker Desktop.exe'
$script:DockerDesktopRelativePath = 'Docker\Docker\Docker Desktop.exe'

function Get-DockerObjectProperty {
    param([AllowNull()]$Object,[Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Invoke-SharedDockerCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)
    try {
        $lines = @(& docker @Arguments 2>&1)
        $exitCode = if ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
        $text = ($lines | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        return [pscustomobject]@{ ExitCode=$exitCode; Output=$text.Trim(); Error=if($exitCode -eq 0){$null}else{$text.Trim()} }
    } catch {
        return [pscustomobject]@{ ExitCode=1; Output=''; Error=$_.Exception.Message }
    }
}

function Get-SharedInfrastructureContracts {
    param([string[]]$Name=@())
    if ($Name.Count -eq 0) { return @($script:SharedInfrastructureContracts) }
    $known = @($script:SharedInfrastructureContracts | Select-Object -ExpandProperty Name)
    $unknown = @($Name | Where-Object { $_ -notin $known })
    if ($unknown.Count -gt 0) { throw "Unknown shared infrastructure contract: $($unknown -join ', ')" }
    return @($script:SharedInfrastructureContracts | Where-Object { $Name -contains $_.Name })
}

function Get-SharedDockerContextName {
    $result = Invoke-SharedDockerCommand -Arguments @('context','show')
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.Output)) { return $null }
    return ($result.Output -split '\r?\n' | Select-Object -First 1).Trim()
}

function Get-SharedCallerContext {
    $identity = ''
    try { $identity = [string][Security.Principal.WindowsIdentity]::GetCurrent().Name } catch {}
    $sandbox = [bool]$env:CODEX_SANDBOX -or $identity -match '(?i)codexsandbox'
    return [pscustomobject]@{ Kind=if($sandbox){'sandbox'}else{'host'}; IsSandbox=$sandbox; Identity=$identity }
}

function Get-DockerDesktopExecutablePath {
    $programFiles = [Environment]::GetEnvironmentVariable('ProgramFiles','Process')
    if ([string]::IsNullOrWhiteSpace($programFiles)) { $programFiles = [Environment]::GetEnvironmentVariable('ProgramFiles','Machine') }
    if ([string]::IsNullOrWhiteSpace($programFiles)) { return $null }
    $path = [IO.Path]::GetFullPath((Join-Path $programFiles $script:DockerDesktopRelativePath))
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    if ([IO.Path]::GetFileName($path) -cne $script:DockerDesktopExecutableName) { return $null }
    return $path
}

function Get-DockerDesktopExecutableIdentity {
    $path = Get-DockerDesktopExecutablePath
    if (-not $path) { return [pscustomobject]@{Ready=$false;Path=$null;FileVersion=$null;ProductName=$null;Sha256=$null;Reason='fixed Docker Desktop executable was not found'} }
    try {
        $item = Get-Item -LiteralPath $path -ErrorAction Stop
        $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($path)
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        $product = [string]$version.ProductName
        $ready = $item.Name -ceq $script:DockerDesktopExecutableName -and $product -match '(?i)^Docker Desktop'
        return [pscustomobject]@{Ready=$ready;Path=$item.FullName;FileVersion=[string]$version.FileVersion;ProductName=$product;Sha256=$hash;Reason=if($ready){$null}else{'Docker Desktop executable identity is not verifiable'}}
    } catch {
        return [pscustomobject]@{Ready=$false;Path=$path;FileVersion=$null;ProductName=$null;Sha256=$null;Reason="Docker Desktop executable identity probe failed: $($_.Exception.Message)"}
    }
}

function Get-ManagedDockerReadinessProbe {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    $missing = [pscustomobject]@{ExitCode=1;Output='';Error='docker CLI is not installed'}
    $client = if($docker){Invoke-SharedDockerCommand -Arguments @('--version')}else{$missing}
    $context = if($docker){Invoke-SharedDockerCommand -Arguments @('context','show')}else{$missing}
    $daemon = if($docker){Invoke-SharedDockerCommand -Arguments @('version','--format','{{.Client.Version}}|{{.Server.Version}}')}else{$missing}
    $desktop = @(Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)
    $backend = @(Get-Process -Name 'com.docker.backend' -ErrorAction SilentlyContinue)
    $identity = Get-DockerDesktopExecutableIdentity
    $daemonText = @($daemon.Output,$daemon.Error) -join [Environment]::NewLine
    $accessDenied = $daemonText -match '(?i)access is denied|permission denied|not permitted|forbidden'
    $cliReady = $null -ne $docker -and $client.ExitCode -eq 0 -and $context.ExitCode -eq 0
    $daemonReady = $daemon.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($daemon.Output)
    $lifecycleReady = $desktop.Count -gt 0 -or $backend.Count -gt 0
    $identityReady = -not $lifecycleReady -or $identity.Ready
    $classification = if(-not $docker){'DAEMON_NOT_READY'}elseif($accessDenied){'CALLER_ACCESS_DENIED'}elseif(-not $lifecycleReady -and -not $daemonReady){'DESKTOP_NOT_RUNNING'}elseif(-not $daemonReady){'DAEMON_NOT_READY'}elseif(-not $identityReady){'DESKTOP_NOT_RUNNING'}elseif(-not $cliReady){'DAEMON_NOT_READY'}else{$null}
    $reason = if($classification){(@($daemon.Error,$context.Error,$identity.Reason)|Where-Object{$_}) -join '; '}else{$null}
    return [pscustomobject]@{
        CliReady=$cliReady; ContextReady=($context.ExitCode -eq 0); DaemonReady=$daemonReady; LifecycleReady=$lifecycleReady
        LifecycleIdentityReady=$identityReady; CallerAccessDenied=$accessDenied; Classification=$classification; Error=$reason
        ContextName=if($context.ExitCode -eq 0){$context.Output.Trim()}else{$null}; ClientVersion=if($client.ExitCode -eq 0){$client.Output.Trim()}else{$null}
        DaemonVersion=if($daemon.ExitCode -eq 0){$daemon.Output.Trim()}else{$null}; DesktopProcessCount=$desktop.Count; BackendProcessCount=$backend.Count
        DesktopIdentity=$identity
    }
}

function Test-ManagedDockerReadiness {
    param([ValidateRange(0,300)][int]$StabilityWindowSeconds=5,[ValidateRange(0,60)][int]$StabilityIntervalSeconds=1,[scriptblock]$ProbeAdapter)
    $probe = if($ProbeAdapter){& $ProbeAdapter}else{Get-ManagedDockerReadinessProbe}
    if($null -eq $probe){return [pscustomobject]@{Outcome='BLOCKED';Classification='DAEMON_NOT_READY';Reason='Docker readiness probe returned no result';StableSamples=0;Probe=$null}}
    if($probe.Classification){return [pscustomobject]@{Outcome='BLOCKED';Classification=[string]$probe.Classification;Reason=[string]$probe.Error;StableSamples=0;Probe=$probe}}
    $samples = 1
    $deadline = [datetime]::UtcNow.AddSeconds($StabilityWindowSeconds)
    while([datetime]::UtcNow -lt $deadline){
        if($StabilityIntervalSeconds -gt 0){Start-Sleep -Seconds $StabilityIntervalSeconds}
        $probe = if($ProbeAdapter){& $ProbeAdapter}else{Get-ManagedDockerReadinessProbe}
        $samples++
        if($null -eq $probe -or $probe.Classification){
            $classification = if($probe){[string]$probe.Classification}else{'DAEMON_NOT_READY'}
            $reason = if($probe){[string]$probe.Error}else{'Docker readiness probe returned no result'}
            return [pscustomobject]@{Outcome='BLOCKED';Classification=$classification;Reason="Docker readiness was not stable for the bounded window: $reason";StableSamples=$samples;Probe=$probe}
        }
    }
    return [pscustomobject]@{Outcome='READY';Classification='MATCH';Reason=$null;StableSamples=$samples;Probe=$probe}
}

function Ensure-ManagedDockerDaemon {
    param([ValidateRange(1,600)][int]$StabilityWindowSeconds=5,[ValidateRange(0,60)][int]$StabilityIntervalSeconds=1,[ValidateRange(1,600)][int]$StartupTimeoutSeconds=180,[scriptblock]$ProbeAdapter)
    $contextName = Get-SharedDockerContextName
    if([string]::IsNullOrWhiteSpace($contextName)){$contextName='unknown'}
    $mutex = New-CoordinationMutex "docker-desktop-managed-lifecycle/$contextName"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $mutex -Deadline ([datetime]::UtcNow.AddSeconds(30))
        if(-not $held){return [pscustomobject]@{Outcome='BLOCKED';Classification='BUSY';Reason='another managed Docker Desktop lifecycle session owns the mutex';Started=$false;StableSamples=0}}
        $initial = Test-ManagedDockerReadiness -StabilityWindowSeconds $StabilityWindowSeconds -StabilityIntervalSeconds $StabilityIntervalSeconds -ProbeAdapter $ProbeAdapter
        if($initial.Outcome -eq 'READY'){return [pscustomobject]@{Outcome='READY';Classification='MATCH';Reason=$null;Started=$false;StableSamples=$initial.StableSamples;Probe=$initial.Probe}}
        $caller = Get-SharedCallerContext
        if($caller.IsSandbox -or $env:AGENT_ASYNC_TOOL_REQUIRED -eq 'true'){
            return [pscustomobject]@{Outcome='BLOCKED';Classification='AGENT_ASYNC_TOOL_REQUIRED';Reason='Docker Desktop is not stable; a host managed launcher must be held by the agent monitor';Started=$false;StableSamples=$initial.StableSamples;Probe=$initial.Probe}
        }
        $managedRoot = 'D:\my-project\my-mobile-secretary'
        $managedRootVariable = Get-Variable -Name ManagedDockerProjectRoot -ErrorAction SilentlyContinue
        if ($managedRootVariable -and $managedRootVariable.Value) { $managedRoot = [string]$managedRootVariable.Value }
        $currentRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\','/')
        $managedRoot = [IO.Path]::GetFullPath($managedRoot).TrimEnd('\','/')
        $formalEntrypoint = Get-Command Invoke-ManagedDockerDesktop -ErrorAction SilentlyContinue
        $formalRootMatch = [string]::Equals($currentRoot,$managedRoot,[StringComparison]::OrdinalIgnoreCase) -or
            $currentRoot.StartsWith("$managedRoot\var\worktrees\",[StringComparison]::OrdinalIgnoreCase)
        if (-not $ProbeAdapter -and $formalEntrypoint -and $formalRootMatch) {
            $formal = Invoke-ManagedDockerDesktop -RepoRoot $currentRoot -TimeoutSeconds $StartupTimeoutSeconds
            $formalReady = $formal.outcome -eq 'READY' -and [bool]$formal.daemonReady
            $formalClass = if ($formalReady) { 'MATCH' } elseif ($formal.failureClassification -eq 'COORDINATION_BUSY') { 'BUSY' } elseif ($formal.failureClassification -eq 'EXECUTABLE_IDENTITY_INVALID') { 'DESKTOP_NOT_RUNNING' } else { 'DAEMON_NOT_READY' }
            $formalProbe = [pscustomobject]@{
                Classification=if($formalReady){$null}else{$formalClass}; Error=if($formalReady){$null}else{[string]$formal.failureClassification}
                ContextName=$formal.dockerContext; ClientVersion=$null; DaemonVersion=$formal.serverVersion
                CliReady=([bool]$formal.cliPath); ContextReady=(-not [string]::IsNullOrWhiteSpace([string]$formal.dockerContext)); DaemonReady=[bool]$formal.daemonReady
            }
            return [pscustomobject]@{
                Outcome=if($formalReady){'READY'}else{'BLOCKED'}; Classification=$formalClass; Reason=if($formalReady){$null}else{"formal managed Docker launcher: $($formal.failureClassification)"}
                Started=([string]$formal.launchDisposition -eq 'started'); StableSamples=0; Probe=$formalProbe; FormalReceipt=$formal
            }
        }
        if (-not $ProbeAdapter -and -not $formalRootMatch) {
            return [pscustomobject]@{Outcome='BLOCKED';Classification=if($initial.Classification){$initial.Classification}else{'DAEMON_NOT_READY'};Reason='Docker Desktop launch is restricted to the verified formal managed entrypoint for the primary repository root';Started=$false;StableSamples=$initial.StableSamples;Probe=$initial.Probe}
        }
        $desktop = @(Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)
        $backend = @(Get-Process -Name 'com.docker.backend' -ErrorAction SilentlyContinue)
        if($desktop.Count -gt 0 -or $backend.Count -gt 0){
            return [pscustomobject]@{Outcome='BLOCKED';Classification=if($initial.Classification){$initial.Classification}else{'DAEMON_NOT_READY'};Reason='Docker Desktop/backend already exists but readiness is not stable; lingering owner was not interrupted';Started=$false;StableSamples=$initial.StableSamples;Probe=$initial.Probe}
        }
        $identity = Get-DockerDesktopExecutableIdentity
        if(-not $identity.Ready){return [pscustomobject]@{Outcome='BLOCKED';Classification='DESKTOP_NOT_RUNNING';Reason=$identity.Reason;Started=$false;StableSamples=$initial.StableSamples}}
        return [pscustomobject]@{Outcome='BLOCKED';Classification=if($initial.Classification){$initial.Classification}else{'DAEMON_NOT_READY'};Reason='Docker Desktop launch requires the verified formal managed entrypoint';Started=$false;StableSamples=$initial.StableSamples;Probe=$initial.Probe;ExecutableIdentity=$identity}
    } finally {
        if($held){$mutex.ReleaseMutex()}
        $mutex.Dispose()
    }
}

function Get-SharedGitPrimaryRoot {
    param([Parameter(Mandatory)][string]$Worktree)
    $full = [IO.Path]::GetFullPath($Worktree).TrimEnd('\','/')
    if(Get-Command Get-EnvironmentGitLayout -ErrorAction SilentlyContinue){$layout=Get-EnvironmentGitLayout -RepoRoot $full}
    else {
        $common = @(& git -C $full rev-parse --git-common-dir 2>$null | Select-Object -First 1)
        if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($common)){throw 'Git common-dir cannot be verified for the requested worktree.'}
        $commonFull = if([IO.Path]::IsPathRooted($common[0])){[IO.Path]::GetFullPath($common[0])}else{[IO.Path]::GetFullPath((Join-Path $full $common[0]))}
        $layout=[pscustomobject]@{RepoRoot=$full;PrimaryRoot=Split-Path -Parent $commonFull;CommonDirectory=$commonFull}
    }
    if(-not $layout.PrimaryRoot -or -not(Test-Path -LiteralPath $layout.PrimaryRoot -PathType Container)){throw 'Git primary root is not available for shared Compose ownership.'}
    return $layout
}

function Get-SharedContainerInspection {
    param([Parameter(Mandatory)][string]$ContainerName)
    $result=Invoke-SharedDockerCommand -Arguments @('inspect','--format','{{json .}}',$ContainerName)
    if($result.ExitCode -ne 0){
        if($result.Error -match '(?i)no such object|no such container|not found'){return [pscustomobject]@{Outcome='MISSING';Container=$null;Error=$result.Error}}
        return [pscustomobject]@{Outcome='BLOCKED';Container=$null;Error=$result.Error}
    }
    try{return [pscustomobject]@{Outcome='FOUND';Container=($result.Output|ConvertFrom-Json -ErrorAction Stop);Error=$null}}
    catch{return [pscustomobject]@{Outcome='BLOCKED';Container=$null;Error='Docker inspect returned invalid JSON.'}}
}

function Test-SharedVolumeContract {
    param([Parameter(Mandatory)]$Container,[Parameter(Mandatory)]$Contract)
    $mounts=@($Container.Mounts|Where-Object{(Get-DockerObjectProperty $_ 'Destination') -eq $Contract.VolumeTarget})
    if($mounts.Count -ne 1){return [pscustomobject]@{Valid=$false;Reason="expected one volume at $($Contract.VolumeTarget)"}}
    $mount=$mounts[0]
    if((Get-DockerObjectProperty $mount 'Type') -ne 'volume' -or -not[bool](Get-DockerObjectProperty $mount 'RW')){return [pscustomobject]@{Valid=$false;Reason='shared mount is not a writable Docker volume'}}
    $name=[string](Get-DockerObjectProperty $mount 'Name')
    if([string]::IsNullOrWhiteSpace($name)){return [pscustomobject]@{Valid=$false;Reason='shared volume name is missing'}}
    $result=Invoke-SharedDockerCommand -Arguments @('volume','inspect','--format','{{json .}}',$name)
    if($result.ExitCode -ne 0){return [pscustomobject]@{Valid=$false;Reason="shared volume could not be inspected: $($result.Error)"}}
    try{$volume=$result.Output|ConvertFrom-Json -ErrorAction Stop}catch{return [pscustomobject]@{Valid=$false;Reason='shared volume inspect returned invalid JSON'}}
    $labels=Get-DockerObjectProperty $volume 'Labels'
    $logical=[string](Get-DockerObjectProperty $labels 'com.docker.compose.volume')
    $project=[string](Get-DockerObjectProperty $labels 'com.docker.compose.project')
    if($logical -ne $Contract.VolumeKey){return [pscustomobject]@{Valid=$false;Reason="shared volume logical identity mismatch (expected $($Contract.VolumeKey), got $logical)";Name=$name;Project=$project}}
    if($project -ne $Contract.ComposeProject){return [pscustomobject]@{Valid=$false;Reason="shared volume Compose project mismatch (expected $($Contract.ComposeProject), got $project)";Name=$name;Project=$project}}
    return [pscustomobject]@{Valid=$true;Reason=$null;Name=$name;Project=$project}
}

function Test-SharedPublishedPortContract {
    param([Parameter(Mandatory)]$Container,[Parameter(Mandatory)]$Contract)
    $network=Get-DockerObjectProperty $Container 'NetworkSettings'
    $ports=Get-DockerObjectProperty $network 'Ports'
    $mapping=Get-DockerObjectProperty $ports "$($Contract.ContainerPort)/$($Contract.Protocol)"
    $matches=@($mapping|Where-Object{[string](Get-DockerObjectProperty $_ 'HostPort') -eq [string]$Contract.HostPort})
    if($matches.Count -eq 0){return [pscustomobject]@{Valid=$false;Reason="published port contract $($Contract.HostPort):$($Contract.ContainerPort)/$($Contract.Protocol) is missing"}}
    return [pscustomobject]@{Valid=$true;Reason=$null}
}

function Get-SharedContainerStatus {
    param([Parameter(Mandatory)]$Contract)
    $inspection=Get-SharedContainerInspection $Contract.ContainerName
    if($inspection.Outcome -eq 'MISSING'){return [pscustomobject]@{Contract=$Contract;ContainerName=$Contract.ContainerName;Outcome='MISSING';ContractValid=$false;State='missing';Health=$null;Classification='SHARED_CONTAINER_STOPPED';Reason='matching shared container is absent';Identity=$null;Volume=$null}}
    if($inspection.Outcome -ne 'FOUND'){return [pscustomobject]@{Contract=$Contract;ContainerName=$Contract.ContainerName;Outcome='BLOCKED';ContractValid=$false;State='unknown';Health=$null;Classification='DAEMON_NOT_READY';Reason=$inspection.Error;Identity=$null;Volume=$null}}
    $container=$inspection.Container
    $config=Get-DockerObjectProperty $container 'Config'
    $labels=Get-DockerObjectProperty $config 'Labels'
    $checks=[ordered]@{
        coordinationClass=([string](Get-DockerObjectProperty $labels 'com.mms.coordination.class') -eq $Contract.CoordinationClass)
        coordinationIdentity=([string](Get-DockerObjectProperty $labels 'com.mms.coordination.identity') -eq $Contract.CoordinationIdentity)
        composeProject=([string](Get-DockerObjectProperty $labels 'com.docker.compose.project') -eq $Contract.ComposeProject)
        composeService=([string](Get-DockerObjectProperty $labels 'com.docker.compose.service') -eq $Contract.ComposeService)
        image=([string](Get-DockerObjectProperty $config 'Image') -eq $Contract.Image)
    }
    $port=Test-SharedPublishedPortContract $container $Contract
    $volume=Test-SharedVolumeContract $container $Contract
    $checks.publishedPort=$port.Valid; $checks.sharedVolume=$volume.Valid
    $failed=@($checks.GetEnumerator()|Where-Object{-not $_.Value}|ForEach-Object{$_.Key})
    $stateObject=Get-DockerObjectProperty $container 'State'
    $state=[string](Get-DockerObjectProperty $stateObject 'Status')
    $health=[string](Get-DockerObjectProperty (Get-DockerObjectProperty $stateObject 'Health') 'Status')
    $identity=[pscustomobject]@{CoordinationClass=[string](Get-DockerObjectProperty $labels 'com.mms.coordination.class');CoordinationIdentity=[string](Get-DockerObjectProperty $labels 'com.mms.coordination.identity');ComposeService=[string](Get-DockerObjectProperty $labels 'com.docker.compose.service');Image=[string](Get-DockerObjectProperty $config 'Image');ContainerId=[string](Get-DockerObjectProperty $container 'Id')}
    $valid=$failed.Count -eq 0
    $classification=if(-not $valid){'SHARED_CONTAINER_IDENTITY_MISMATCH'}elseif($state -ne 'running'){'SHARED_CONTAINER_STOPPED'}elseif($health -ne 'healthy'){'SHARED_CONTAINER_UNHEALTHY'}else{$null}
    $reasons=[Collections.Generic.List[string]]::new()
    if($failed.Count -gt 0){$reasons.Add("contract mismatch: $($failed -join ', ')")}
    if($state -ne 'running'){$reasons.Add("state=$state")}
    if($state -eq 'running' -and $health -ne 'healthy'){$reasons.Add("health=$health")}
    return [pscustomobject]@{Contract=$Contract;ContainerName=$Contract.ContainerName;Outcome='FOUND';ContractValid=$valid;State=$state;Health=$health;Classification=$classification;Reason=($reasons -join '; ');Identity=$identity;Volume=$volume;Checks=[pscustomobject]$checks}
}

function Test-SharedHostPort {
    param([Parameter(Mandatory)][int]$Port,[ValidateRange(1,30)][int]$TimeoutSeconds=1)
    $deadline=[datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while([datetime]::UtcNow -lt $deadline){
        $client=[Net.Sockets.TcpClient]::new()
        try{$task=$client.ConnectAsync('127.0.0.1',$Port);if($task.Wait(500) -and $client.Connected){return $true}}catch{}finally{$client.Dispose()}
        Start-Sleep -Milliseconds 100
    }
    return $false
}

function Get-SharedInfrastructureStatus {
    param([string[]]$Name=@(),[switch]$SkipDockerReadiness,[ValidateRange(0,300)][int]$DockerStabilityWindowSeconds=5,[scriptblock]$ProbeAdapter)
    $readiness=$null
    if(-not $SkipDockerReadiness){
        $readiness=Test-ManagedDockerReadiness -StabilityWindowSeconds $DockerStabilityWindowSeconds -ProbeAdapter $ProbeAdapter
        if($readiness.Outcome -ne 'READY'){return [pscustomobject]@{Outcome='BLOCKED';Classification=$readiness.Classification;Reason=$readiness.Reason;Containers=@();Readiness=$readiness;Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
    }
    $contracts=Get-SharedInfrastructureContracts $Name
    $containers=@($contracts|ForEach-Object{Get-SharedContainerStatus $_})
    $bad=@($containers|Where-Object{$_.Outcome -eq 'BLOCKED' -or ($_.Outcome -eq 'FOUND' -and -not $_.ContractValid)})
    if($bad.Count -gt 0){$first=$bad[0];return [pscustomobject]@{Outcome='BLOCKED';Classification=$first.Classification;Reason=(($bad|ForEach-Object{"$($_.ContainerName): $($_.Reason)"}) -join '; ');Containers=$containers;Readiness=$null;Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
    $missing=@($containers|Where-Object{$_.Outcome -eq 'MISSING'})
    $stopped=@($containers|Where-Object{$_.Outcome -eq 'FOUND' -and $_.State -ne 'running'})
    $unhealthy=@($containers|Where-Object{$_.Outcome -eq 'FOUND' -and $_.State -eq 'running' -and $_.Health -ne 'healthy'})
    $outcome=if($missing.Count -gt 0){'MISSING'}elseif($stopped.Count -gt 0){'STOPPED'}elseif($unhealthy.Count -gt 0){'UNHEALTHY'}else{'READY'}
    $classification=if($missing.Count -gt 0 -or $stopped.Count -gt 0){'SHARED_CONTAINER_STOPPED'}elseif($unhealthy.Count -gt 0){'SHARED_CONTAINER_UNHEALTHY'}else{'MATCH'}
    return [pscustomobject]@{Outcome=$outcome;Classification=$classification;Reason=if($outcome -eq 'READY'){$null}else{"$outcome shared infrastructure requires bounded reconciliation"};Containers=$containers;Readiness=$readiness;Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}
}

function Wait-SharedInfrastructureHealthy {
    param([Parameter(Mandatory)][object[]]$Contracts,[ValidateRange(1,600)][int]$TimeoutSeconds=60)
    $deadline=[datetime]::UtcNow.AddSeconds($TimeoutSeconds); $last=$null
    while([datetime]::UtcNow -lt $deadline){
        $last=Get-SharedInfrastructureStatus -Name @($Contracts|Select-Object -ExpandProperty Name) -SkipDockerReadiness
        if($last.Outcome -eq 'BLOCKED'){return $last}
        $healthy=@($last.Containers|Where-Object{$_.Outcome -ne 'FOUND' -or -not $_.ContractValid -or $_.State -ne 'running' -or $_.Health -ne 'healthy'}).Count -eq 0
        $ports=@($Contracts|Where-Object{-not(Test-SharedHostPort $_.HostPort 1)}).Count -eq 0
        if($healthy -and $ports){return $last}
        Start-Sleep -Seconds 1
    }
    if($last){return $last}
    return [pscustomobject]@{Outcome='BLOCKED';Classification='SHARED_CONTAINER_UNHEALTHY';Reason='shared infrastructure did not become healthy within the bounded timeout';Containers=@();Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}
}

function Ensure-SharedInfrastructure {
    param([string[]]$Name=@('main-postgres','main-redis'),[ValidateRange(1,600)][int]$HealthTimeoutSeconds=60,[ValidateRange(0,300)][int]$DockerStabilityWindowSeconds=5,[ValidateRange(0,60)][int]$DockerStabilityIntervalSeconds=1,[ValidateRange(1,600)][int]$DockerStartupTimeoutSeconds=180,[scriptblock]$DockerProbeAdapter)
    $contracts=Get-SharedInfrastructureContracts $Name
    $docker=Ensure-ManagedDockerDaemon -StabilityWindowSeconds $DockerStabilityWindowSeconds -StabilityIntervalSeconds $DockerStabilityIntervalSeconds -StartupTimeoutSeconds $DockerStartupTimeoutSeconds -ProbeAdapter $DockerProbeAdapter
    if($docker.Outcome -ne 'READY'){return [pscustomobject]@{Outcome='BLOCKED';Classification=$docker.Classification;Reason=$docker.Reason;Docker=$docker;Containers=@();Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
    $context=if($docker.Probe){$docker.Probe.ContextName}else{Get-SharedDockerContextName}; if([string]::IsNullOrWhiteSpace($context)){$context='unknown'}
    $mutex=New-CoordinationMutex "shared-container-lifecycle/$context"; $held=$false
    try{
        $held=Wait-CoordinationMutex -Mutex $mutex -Deadline ([datetime]::UtcNow.AddSeconds(30))
        if(-not $held){return [pscustomobject]@{Outcome='BLOCKED';Classification='BUSY';Reason='another session owns shared-container lifecycle mutex';Docker=$docker;Containers=@();Mutation=[ordered]@{Started=@();Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
        $status=Get-SharedInfrastructureStatus -Name $Name -SkipDockerReadiness
        if($status.Outcome -eq 'BLOCKED'){return [pscustomobject]@{Outcome='BLOCKED';Classification=$status.Classification;Reason=$status.Reason;Docker=$docker;Containers=$status.Containers;Mutation=$status.Mutation}}
        $started=[Collections.Generic.List[string]]::new(); $created=[Collections.Generic.List[string]]::new()
        foreach($entry in @($status.Containers|Where-Object{$_.Outcome -eq 'FOUND' -and $_.State -ne 'running'})){
            $start=Invoke-SharedDockerCommand -Arguments @('start',$entry.ContainerName)
            if($start.ExitCode -ne 0){$class=if($start.Error -match '(?i)conflict.*name|already in use'){'WORKTREE_COMPOSE_NAME_CONFLICT'}else{'SHARED_CONTAINER_STOPPED'};return [pscustomobject]@{Outcome='BLOCKED';Classification=$class;Reason="matching shared container was not started: $($start.Error)";Docker=$docker;Containers=$status.Containers;Mutation=[ordered]@{Started=@($started);Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
            $started.Add($entry.ContainerName)
        }
        foreach($entry in @($status.Containers|Where-Object{$_.Outcome -eq 'MISSING'})){
            $primary=Get-SharedGitPrimaryRoot -Worktree (Split-Path -Parent $PSScriptRoot); $composeFile=Join-Path $primary.PrimaryRoot $entry.Contract.ComposeFile
            if(-not(Test-Path -LiteralPath $composeFile -PathType Leaf)){return [pscustomobject]@{Outcome='BLOCKED';Classification='SHARED_CONTAINER_STOPPED';Reason="approved primary Compose file is missing: $composeFile";Docker=$docker;Containers=$status.Containers;Mutation=[ordered]@{Started=@($started);Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
            $compose=Invoke-SharedDockerCommand -Arguments @('compose','--project-directory',$primary.PrimaryRoot,'-f',$composeFile,'up','-d',$entry.Contract.ComposeService)
            if($compose.ExitCode -ne 0){$class=if($compose.Error -match '(?i)conflict.*name|already in use'){'WORKTREE_COMPOSE_NAME_CONFLICT'}else{'SHARED_CONTAINER_STOPPED'};return [pscustomobject]@{Outcome='BLOCKED';Classification=$class;Reason="approved primary Compose could not create the missing shared container: $($compose.Error)";Docker=$docker;Containers=$status.Containers;Mutation=[ordered]@{Started=@($started);Created=@();Removed=@();Replaced=@();VolumesChanged=$false}}}
            $created.Add($entry.ContainerName)
        }
        $verified=Wait-SharedInfrastructureHealthy -Contracts $contracts -TimeoutSeconds $HealthTimeoutSeconds
        if($verified.Outcome -ne 'READY'){return [pscustomobject]@{Outcome='BLOCKED';Classification=$verified.Classification;Reason=$verified.Reason;Docker=$docker;Containers=$verified.Containers;Mutation=[ordered]@{Started=@($started);Created=@($created);Removed=@();Replaced=@();VolumesChanged=$false}}}
        $action=if($created.Count -gt 0){'CREATED_MISSING'}elseif($started.Count -gt 0){'STARTED_MATCHING_STOPPED'}else{'REUSED_HEALTHY'}
        return [pscustomobject]@{Outcome='READY';Classification='MATCH';Reason=$null;Action=$action;Docker=$docker;Containers=$verified.Containers;Mutation=[ordered]@{Started=@($started);Created=@($created);Removed=@();Replaced=@();VolumesChanged=$false}}
    }finally{if($held){$mutex.ReleaseMutex()};$mutex.Dispose()}
}

function New-SharedInfrastructureReceipt {
    param([Parameter(Mandatory)]$Result,[Parameter(Mandatory)][string]$OperationId,[string]$Worktree)
    $input=@($script:SharedInfrastructureContracts|ForEach-Object{"$($_.Name)|$($_.ContainerName)|$($_.CoordinationClass)|$($_.CoordinationIdentity)|$($_.ComposeProject)|$($_.ComposeService)|$($_.Image)|$($_.HostPort)|$($_.ContainerPort)|$($_.VolumeKey)|$($_.VolumeTarget)"}) -join [Environment]::NewLine
    $fingerprint=if(Get-Command Get-CoordinationHash -ErrorAction SilentlyContinue){(Get-CoordinationHash $input).Substring(0,24)}else{$null}
    return [ordered]@{schemaVersion=$script:SharedInfrastructureSchemaVersion;operationId=$OperationId;outcome=$Result.Outcome;classification=$Result.Classification;action=if($Result.PSObject.Properties['Action']){$Result.Action}else{$null};reason=$Result.Reason;worktree=if($Worktree){[IO.Path]::GetFullPath($Worktree).TrimEnd('\','/')}else{$null};contractFingerprint=$fingerprint;mutation=$Result.Mutation;containers=@($Result.Containers|ForEach-Object{[ordered]@{name=$_.ContainerName;state=$_.State;health=$_.Health;classification=$_.Classification;identity=$_.Identity;volume=$_.Volume}})}
}

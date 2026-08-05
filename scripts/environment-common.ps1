Set-StrictMode -Version Latest

. "$PSScriptRoot\coordination-common.ps1"
. "$PSScriptRoot\docker-shared-infrastructure.ps1"

$script:EnvironmentSchemaVersion = 2
$script:EnvironmentCapabilityNames = @(
    'READ_ONLY', 'SOURCE_WRITE', 'MAVEN', 'DOCKER_TEST',
    'DEV_RUNTIME', 'LINE_E2E', 'EXTERNAL_PROVIDER'
)
$script:EnvironmentReadyStates = @('MATCH', 'COMPATIBLE_DRIFT')

function ConvertTo-EnvironmentArgument {
    param([AllowEmptyString()][string]$Value)
    if ($Value -notmatch '[\s"&|<>^()]') { return $Value }
    return '"{0}"' -f ($Value -replace '(\\*)"', '$1$1\"')
}

function Invoke-EnvironmentProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$RawArguments,
        [string]$WorkingDirectory,
        [hashtable]$Environment,
        [ValidateRange(100, 120000)][int]$TimeoutMilliseconds = 5000
    )
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = if ($RawArguments) { $RawArguments } else { (@($Arguments | ForEach-Object { ConvertTo-EnvironmentArgument $_ }) -join ' ') }
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if ($WorkingDirectory) { $startInfo.WorkingDirectory = $WorkingDirectory }
    $environmentBackup = @{}

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        # Windows PowerShell hosted in the Codex sandbox can expose a null
        # ProcessStartInfo.EnvironmentVariables collection. Apply overrides only to this
        # PowerShell process for the child creation window, then restore them in finally.
        if ($Environment) {
            foreach ($name in $Environment.Keys) {
                $environmentBackup[[string]$name] = [Environment]::GetEnvironmentVariable([string]$name, 'Process')
                [Environment]::SetEnvironmentVariable([string]$name, [string]$Environment[$name], 'Process')
            }
        }
        if (-not $process.Start()) {
            return [pscustomobject]@{ ExitCode = 1; TimedOut = $false; Output = ''; Error = 'process did not start'; DurationMilliseconds = 0 }
        }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            try { $process.Kill() } catch { }
            try { $process.WaitForExit(2000) | Out-Null } catch { }
            return [pscustomobject]@{
                ExitCode = 124; TimedOut = $true; Output = ''; Error = "timeout after $TimeoutMilliseconds ms"
                DurationMilliseconds = [int]$stopwatch.ElapsedMilliseconds
            }
        }
        return [pscustomobject]@{
            ExitCode = [int]$process.ExitCode
            TimedOut = $false
            Output = [string]$stdout.Result
            Error = [string]$stderr.Result
            DurationMilliseconds = [int]$stopwatch.ElapsedMilliseconds
        }
    } catch {
        return [pscustomobject]@{
            ExitCode = 1; TimedOut = $false; Output = ''; Error = $_.Exception.Message
            DurationMilliseconds = [int]$stopwatch.ElapsedMilliseconds
        }
    } finally {
        foreach ($name in $environmentBackup.Keys) {
            [Environment]::SetEnvironmentVariable([string]$name, $environmentBackup[$name], 'Process')
        }
        $stopwatch.Stop()
        $process.Dispose()
    }
}

function Get-EnvironmentGitLayout {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $fullRoot = [IO.Path]::GetFullPath($RepoRoot)
    $dotGit = Join-Path $fullRoot '.git'
    $gitDirectory = $null
    if (Test-Path -LiteralPath $dotGit -PathType Container) {
        $gitDirectory = [IO.Path]::GetFullPath($dotGit)
    } elseif (Test-Path -LiteralPath $dotGit -PathType Leaf) {
        $pointer = [IO.File]::ReadAllText($dotGit, [Text.Encoding]::UTF8).Trim()
        if ($pointer -match '^gitdir:\s*(?<path>.+)$') {
            $candidate = $Matches.path.Trim()
            if (-not [IO.Path]::IsPathRooted($candidate)) { $candidate = Join-Path $fullRoot $candidate }
            $gitDirectory = [IO.Path]::GetFullPath($candidate)
        }
    }
    $commonDirectory = $gitDirectory
    if ($gitDirectory) {
        $commonPointer = Join-Path $gitDirectory 'commondir'
        if (Test-Path -LiteralPath $commonPointer -PathType Leaf) {
            $candidate = [IO.File]::ReadAllText($commonPointer, [Text.Encoding]::UTF8).Trim()
            if (-not [IO.Path]::IsPathRooted($candidate)) { $candidate = Join-Path $gitDirectory $candidate }
            $commonDirectory = [IO.Path]::GetFullPath($candidate)
        }
    }
    $primaryRoot = if ($commonDirectory -and (Split-Path -Leaf $commonDirectory) -eq '.git') {
        Split-Path -Parent $commonDirectory
    } else { $fullRoot }
    return [pscustomobject]@{ RepoRoot=$fullRoot;PrimaryRoot=$primaryRoot;GitDirectory=$gitDirectory;CommonDirectory=$commonDirectory }
}

function Normalize-EnvironmentPath {
    param([Parameter(Mandatory)][string]$Path)
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

function Get-EnvironmentRegisteredWorktrees {
    param([string]$AnchorWorktree = (Split-Path -Parent $PSScriptRoot))
    $anchorLayout = Get-EnvironmentGitLayout -RepoRoot $AnchorWorktree
    $lines = @(& git -C $anchorLayout.PrimaryRoot worktree list --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Git registered worktree list could not be verified.' }
    $entries = [Collections.Generic.List[object]]::new()
    $current = $null
    foreach ($line in $lines) {
        if ($line -match '^worktree\s+(?<path>.+)$') {
            if ($current) { $entries.Add([pscustomobject]$current) }
            $current = [ordered]@{ Path=(Normalize-EnvironmentPath $Matches.path.Trim()); Head=$null; Branch=$null }
        } elseif ($current -and $line -match '^HEAD\s+(?<head>[0-9a-fA-F]{40})$') {
            $current.Head = $Matches.head.ToLowerInvariant()
        } elseif ($current -and $line -match '^branch\s+(?<branch>.+)$') {
            $current.Branch = $Matches.branch
        }
    }
    if ($current) { $entries.Add([pscustomobject]$current) }
    return @($entries)
}

function Get-EnvironmentWorktreeIdentity {
    param([Parameter(Mandatory)][string]$Worktree,[string]$AnchorWorktree)
    $target = Normalize-EnvironmentPath $Worktree
    $layout = Get-EnvironmentGitLayout -RepoRoot $target
    $anchor = if ($AnchorWorktree) { Normalize-EnvironmentPath $AnchorWorktree } else { $layout.PrimaryRoot }
    $anchorLayout = Get-EnvironmentGitLayout -RepoRoot $anchor
    $registered = @(Get-EnvironmentRegisteredWorktrees -AnchorWorktree $anchor)
    $entry = @($registered | Where-Object { [string]::Equals($_.Path,$target,[StringComparison]::OrdinalIgnoreCase) }) | Select-Object -First 1
    $common = Normalize-EnvironmentPath $layout.CommonDirectory
    $anchorCommon = Normalize-EnvironmentPath $anchorLayout.CommonDirectory
    $sameCommon = [string]::Equals($common,$anchorCommon,[StringComparison]::OrdinalIgnoreCase)
    $repo = Get-EnvironmentRepositoryIdentity -RepoRoot $target
    $worktreeId = (Get-CoordinationHash ("$common|$target")).Substring(0,24)
    return [pscustomobject]@{
        Path=$target; PrimaryRoot=(Normalize-EnvironmentPath $layout.PrimaryRoot); CommonDirectory=$common
        Registered=($null -ne $entry); SameCommonDirectory=$sameCommon; RepoId=$repo.RepoId
        WorktreeId=$worktreeId; Head=if($entry){$entry.Head}else{$null}; Branch=if($entry){$entry.Branch}else{$null}
        IsPrimary=[string]::Equals($target,(Normalize-EnvironmentPath $layout.PrimaryRoot),[StringComparison]::OrdinalIgnoreCase)
    }
}

function Assert-EnvironmentTargetWorktree {
    param([Parameter(Mandatory)][string]$TargetWorktree,[Parameter(Mandatory)][string]$AnchorWorktree)
    $identity = Get-EnvironmentWorktreeIdentity -Worktree $TargetWorktree -AnchorWorktree $AnchorWorktree
    if (-not $identity.Registered) { throw "Target worktree is not registered by Git: $($identity.Path)" }
    if (-not $identity.SameCommonDirectory) { throw 'Target worktree belongs to a different Git common-dir/repository.' }
    if (-not (Test-Path -LiteralPath (Join-Path $identity.Path '.git'))) { throw 'Target worktree Git metadata is missing.' }
    return $identity
}

function Get-EnvironmentOriginFromConfig {
    param([string]$CommonDirectory)
    if (-not $CommonDirectory) { return $null }
    $configPath = Join-Path $CommonDirectory 'config'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { return $null }
    $inOrigin = $false
    foreach ($line in [IO.File]::ReadAllLines($configPath, [Text.Encoding]::UTF8)) {
        if ($line -match '^\s*\[') { $inOrigin = $line -match '^\s*\[remote\s+"origin"\]\s*$'; continue }
        if ($inOrigin -and $line -match '^\s*url\s*=\s*(?<url>.+?)\s*$') { return $Matches.url }
    }
    return $null
}

function Get-EnvironmentRepositoryIdentity {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $layout = Get-EnvironmentGitLayout -RepoRoot $RepoRoot
    $origin = Get-EnvironmentOriginFromConfig -CommonDirectory $layout.CommonDirectory
    $fullRoot = $layout.RepoRoot
    $identitySource = if ($origin) { $origin.ToLowerInvariant() } else { $fullRoot.ToLowerInvariant() }
    return [pscustomobject]@{
        RepoRoot = $fullRoot
        RepoId = (Get-CoordinationHash $identitySource).Substring(0, 24)
        OriginPresent = [bool]$origin
    }
}

function Get-EnvironmentDefaultRoot {
    if ($env:MMS_ENVIRONMENT_STATE_ROOT) {
        return [IO.Path]::GetFullPath($env:MMS_ENVIRONMENT_STATE_ROOT)
    }
    # Codex sandbox identities can read the normal user's LOCALAPPDATA coordination
    # registry but cannot create new directories there. Use the ignored main-worktree
    # var directory as the sandbox/host bridge; caller-specific snapshots prevent a
    # host result from being reused as sandbox readiness (or the reverse).
    $layout = Get-EnvironmentGitLayout -RepoRoot (Split-Path -Parent $PSScriptRoot)
    return Join-Path $layout.PrimaryRoot 'var\environment-state\v1'
}

function Get-EnvironmentMachineAlias {
    param([string]$StateRoot = (Get-EnvironmentDefaultRoot), [string]$MachineAlias)
    if ($MachineAlias) {
        if ($MachineAlias -notmatch '^[a-z0-9][a-z0-9-]{0,31}$') { throw 'Machine alias must contain only lowercase letters, digits, and hyphens.' }
        return $MachineAlias
    }
    if ($env:MMS_MACHINE_ALIAS) { return Get-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias $env:MMS_MACHINE_ALIAS }
    $aliasPath = Join-Path $StateRoot 'machine-alias.txt'
    if (Test-Path -LiteralPath $aliasPath -PathType Leaf) {
        return Get-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias ([IO.File]::ReadAllText($aliasPath, [Text.Encoding]::UTF8).Trim())
    }
    $computerName = [string]$env:COMPUTERNAME
    if ($computerName -match '(?i)laptop|notebook') { return 'laptop' }
    if ($computerName -match '(?i)desktop') { return 'desktop' }
    if ([string]::IsNullOrWhiteSpace($computerName)) { return 'machine-unknown' }
    return 'machine-{0}' -f (Get-CoordinationHash $computerName.ToLowerInvariant()).Substring(0, 8)
}

function Assert-EnvironmentMachineAlias {
    param([Parameter(Mandatory)][string]$StateRoot,[Parameter(Mandatory)][string]$MachineAlias)
    $validated = Get-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias $MachineAlias
    $aliasPath = Join-Path $StateRoot 'machine-alias.txt'
    if (Test-Path -LiteralPath $aliasPath -PathType Leaf) {
        $stored = [IO.File]::ReadAllText($aliasPath, [Text.Encoding]::UTF8).Trim()
        if (-not [string]::Equals($stored,$validated,[StringComparison]::Ordinal)) {
            throw "Machine alias does not match the verified state root: expected $stored, got $validated"
        }
    }
    if ($env:MMS_MACHINE_ALIAS -and -not [string]::Equals($env:MMS_MACHINE_ALIAS,$validated,[StringComparison]::Ordinal)) {
        throw "Machine alias does not match MMS_MACHINE_ALIAS."
    }
    return $validated
}

function Set-EnvironmentMachineAlias {
    param([Parameter(Mandatory)][string]$MachineAlias, [string]$StateRoot = (Get-EnvironmentDefaultRoot))
    $validated = Get-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias $MachineAlias
    [IO.Directory]::CreateDirectory($StateRoot) | Out-Null
    [IO.File]::WriteAllText((Join-Path $StateRoot 'machine-alias.txt'), $validated, [Text.UTF8Encoding]::new($false))
    return $validated
}

function Get-EnvironmentStateContext {
    param(
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [string]$StateRoot = (Get-EnvironmentDefaultRoot),
        [string]$MachineAlias
    )
    $repository = Get-EnvironmentRepositoryIdentity -RepoRoot $RepoRoot
    $alias = Get-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias $MachineAlias
    $machineRoot = Join-Path (Join-Path (Join-Path $StateRoot 'repos') $repository.RepoId) (Join-Path 'machines' $alias)
    $callerKind = (Get-EnvironmentCallerContext).Kind
    return [pscustomobject]@{
        RepoRoot = $repository.RepoRoot; RepoId = $repository.RepoId; MachineAlias = $alias; CallerKind=$callerKind
        StateRoot = $StateRoot; MachineRoot = $machineRoot
        SnapshotPath = Join-Path $machineRoot "snapshot-$callerKind.json"
        DemandsPath = Join-Path $machineRoot 'demands'
        DemandPath = Join-Path $machineRoot 'demand.json'
        IssuesPath = Join-Path $machineRoot 'issues'
        ReviewsPath = Join-Path $machineRoot 'reviews'
        ReceiptsPath = Join-Path $machineRoot 'receipts'
        GitHubConfigPath = Join-Path $machineRoot 'github.json'
    }
}

function Get-EnvironmentSnapshotPath {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [string]$CallerKind = $Context.CallerKind
    )
    return Join-Path $Context.MachineRoot ("snapshot-{0}-{1}.json" -f $CallerKind,$Capability.ToLowerInvariant())
}

function Get-EnvironmentDemandPath {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [string]$CallerKind = $Context.CallerKind
    )
    return Join-Path $Context.DemandsPath ("demand-{0}-{1}.json" -f $CallerKind,$Capability.ToLowerInvariant())
}

function Get-EnvironmentMavenVersion {
    param([Parameter(Mandatory)][string]$RepoRoot)
    $propertiesPath = Join-Path $RepoRoot '.mvn\wrapper\maven-wrapper.properties'
    if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) { return $null }
    $line = Get-Content -Encoding UTF8 -LiteralPath $propertiesPath | Where-Object { $_ -like 'distributionUrl=*' } | Select-Object -First 1
    if ($line -match 'apache-maven-([0-9.]+)-bin\.zip') { return $Matches[1] }
    return $null
}

function Get-EnvironmentContract {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $mavenVersion = Get-EnvironmentMavenVersion -RepoRoot $RepoRoot
    $fingerprint = Get-CoordinationHash ("java=21|maven=$mavenVersion|schema=$script:EnvironmentSchemaVersion")
    return [pscustomobject]@{
        SchemaVersion = $script:EnvironmentSchemaVersion
        JavaMajor = 21
        MavenWrapperVersion = $mavenVersion
        SnapshotTtlMinutes = 5
        PublishedTtlMinutes = 30
        MonitorIntervalMinutes = 15
        Fingerprint = $fingerprint.Substring(0, 24)
    }
}

function Get-JavaMajorFromOutput {
    param([string]$Output)
    if ($Output -match 'version\s+"(?<major>[0-9]+)(?:\.|"|-)') { return [int]$Matches.major }
    if ($Output -match 'openjdk\s+(?<major>[0-9]+)(?:\.|\s)') { return [int]$Matches.major }
    return $null
}

function Invoke-JavaVersionProbe {
    param([Parameter(Mandatory)][string]$JavaPath)
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        return [pscustomobject]@{ Ready = $false; Path = $JavaPath; Major = $null; VersionText = $null; Reason = 'java executable missing' }
    }
    $result = Invoke-EnvironmentProcess -FilePath $JavaPath -Arguments @('-version') -TimeoutMilliseconds 5000
    $combined = (($result.Output, $result.Error) -join "`n").Trim()
    $major = Get-JavaMajorFromOutput -Output $combined
    return [pscustomobject]@{
        Ready = $result.ExitCode -eq 0 -and $null -ne $major
        Path = $JavaPath; Major = $major
        VersionText = if ($combined) { ($combined -split "`r?`n" | Select-Object -First 1) } else { $null }
        Reason = if ($result.TimedOut) { 'java version timed out' } elseif ($result.ExitCode -ne 0) { 'java version failed' } else { $null }
    }
}

function Get-EnvironmentJavaProbe {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $contract = Get-EnvironmentContract -RepoRoot $RepoRoot
    $configuredHome = [string]$env:JAVA_HOME
    $configuredJava = if ($configuredHome) { Join-Path $configuredHome 'bin\java.exe' } else { $null }
    $configuredProbe = if ($configuredJava) { Invoke-JavaVersionProbe -JavaPath $configuredJava } else {
        [pscustomobject]@{ Ready = $false; Path = $null; Major = $null; VersionText = $null; Reason = 'JAVA_HOME is not set' }
    }
    $pathCommand = Get-Command java -ErrorAction SilentlyContinue
    $pathJava = if ($pathCommand) { $pathCommand.Source } else { $null }
    $pathProbe = if ($pathJava) { Invoke-JavaVersionProbe -JavaPath $pathJava } else {
        [pscustomobject]@{ Ready = $false; Path = $null; Major = $null; VersionText = $null; Reason = 'java is not on PATH' }
    }
    $caller = Get-EnvironmentCallerContext
    $userHome = if (-not $caller.IsSandbox) {
        try { [string][Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') } catch { $null }
    } else { $null }
    $userJava = if ($userHome) { Join-Path $userHome 'bin\java.exe' } else { $null }
    $userProbe = if (-not $userJava) {
        [pscustomobject]@{ Ready = $false; Path = $null; Major = $null; VersionText = $null; Reason = 'user-level JAVA_HOME is unavailable' }
    } elseif ($configuredJava -and [string]::Equals($userJava, $configuredJava, [StringComparison]::OrdinalIgnoreCase)) {
        $configuredProbe
    } elseif ($pathJava -and [string]::Equals($userJava, $pathJava, [StringComparison]::OrdinalIgnoreCase)) {
        $pathProbe
    } else {
        Invoke-JavaVersionProbe -JavaPath $userJava
    }
    $persistentConfigurationReady = -not $caller.IsSandbox -and $userProbe.Ready -and $userProbe.Major -eq $contract.JavaMajor
    $candidateHome = $null
    if ($configuredProbe.Ready -and $configuredProbe.Major -eq $contract.JavaMajor) {
        $candidateHome = $configuredHome
    } elseif ($persistentConfigurationReady) {
        $candidateHome = $userHome
    } elseif ($pathProbe.Ready -and $pathProbe.Major -eq $contract.JavaMajor) {
        $candidateHome = Split-Path -Parent (Split-Path -Parent $pathJava)
    }

    $mavenResult = $null
    $wrapper = Join-Path $RepoRoot 'mvnw.cmd'
    if ($candidateHome -and (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        $mavenResult = Invoke-EnvironmentProcess -FilePath $env:ComSpec `
            -RawArguments ('/d /s /c ""{0}" -v"' -f $wrapper) `
            -WorkingDirectory $RepoRoot -Environment @{ JAVA_HOME = $candidateHome } -TimeoutMilliseconds 20000
    }
    $mavenReady = $mavenResult -and $mavenResult.ExitCode -eq 0
    $state = 'ACTION_REQUIRED'
    $reason = 'no compatible Java 21 installation was found'
    if ($candidateHome -and $mavenReady) {
        if ($configuredProbe.Ready -and $configuredProbe.Major -eq $contract.JavaMajor) {
            $state = if ($pathProbe.Ready -and $pathProbe.Major -eq $contract.JavaMajor -and
                -not [string]::Equals($configuredProbe.Path, $pathProbe.Path, [StringComparison]::OrdinalIgnoreCase)) {
                'COMPATIBLE_DRIFT'
            } else { 'MATCH' }
            $reason = $null
        } else {
            $state = 'ACTION_REQUIRED'
            $reason = if ($persistentConfigurationReady) {
                'current process JAVA_HOME is stale but user-level Java 21 can run Maven Wrapper'
            } else {
                'JAVA_HOME is invalid but a compatible Java 21 candidate can run Maven Wrapper'
            }
        }
    } elseif ($candidateHome) {
        $reason = 'Java 21 was found but Maven Wrapper validation failed'
    }
    return [pscustomobject]@{
        State = $state; Ready = $script:EnvironmentReadyStates -contains $state
        ConfiguredHome = $configuredHome; ConfiguredJava = $configuredProbe
        PathJava = $pathProbe; UserHome = $userHome; UserJava = $userProbe
        PersistentConfigurationReady = [bool]$persistentConfigurationReady; CandidateHome = $candidateHome
        MavenReady = [bool]$mavenReady
        MavenExitCode = if ($mavenResult) { $mavenResult.ExitCode } else { $null }
        Reason = $reason
    }
}

function Resolve-EnvironmentJavaHome {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot), [switch]$ApplyProcess)
    $probe = Get-EnvironmentJavaProbe -RepoRoot $RepoRoot
    if (-not $probe.CandidateHome -or -not $probe.MavenReady) {
        throw "Java environment is ACTION_REQUIRED: $($probe.Reason)"
    }
    if ($ApplyProcess) { $env:JAVA_HOME = $probe.CandidateHome }
    return $probe
}

function Get-EnvironmentGitProbe {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { return [pscustomobject]@{ State = 'ACTION_REQUIRED'; Ready = $false; Reason = 'git is not installed'; Version = $null } }
    $version = Invoke-EnvironmentProcess -FilePath $git.Source -Arguments @('--version') -TimeoutMilliseconds 3000
    if ($version.ExitCode -ne 0) { $version = Invoke-EnvironmentProcess -FilePath $git.Source -Arguments @('--version') -TimeoutMilliseconds 6000 }
    $repository = Invoke-EnvironmentProcess -FilePath $git.Source `
        -Arguments @('-C', $RepoRoot, 'rev-parse', '--git-common-dir', '--verify', 'HEAD') -TimeoutMilliseconds 5000
    if ($repository.ExitCode -ne 0) {
        $repository = Invoke-EnvironmentProcess -FilePath $git.Source `
            -Arguments @('-C', $RepoRoot, 'rev-parse', '--git-common-dir', '--verify', 'HEAD') -TimeoutMilliseconds 10000
    }
    $layout = Get-EnvironmentGitLayout -RepoRoot $RepoRoot
    $originReady = [bool](Get-EnvironmentOriginFromConfig -CommonDirectory $layout.CommonDirectory)
    $ready = $version.ExitCode -eq 0 -and $repository.ExitCode -eq 0 -and $originReady
    $timedOut = $version.TimedOut -or $repository.TimedOut
    $state = if ($ready) { 'MATCH' } elseif ($timedOut) { 'UNKNOWN' } else { 'ACTION_REQUIRED' }
    return [pscustomobject]@{
        State = $state; Ready = $ready
        Version = if ($version.ExitCode -eq 0) { $version.Output.Trim() } else { $null }
        CommonDirReady = $repository.ExitCode -eq 0; HeadReady = $repository.ExitCode -eq 0; OriginReady = $originReady
        Reason = if ($ready) { $null } elseif ($timedOut) { 'required Git repository probe timed out after one bounded retry' } else { 'required Git repository commands failed' }
    }
}

function Get-EnvironmentPowerShellProbe {
    $ready = $PSVersionTable.PSVersion.Major -ge 5 -and [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
    return [pscustomobject]@{
        State = if ($ready) { 'MATCH' } else { 'ACTION_REQUIRED' }; Ready = $ready
        Version = $PSVersionTable.PSVersion.ToString(); Edition = [string]$PSVersionTable.PSEdition
        Reason = if ($ready) { $null } else { 'Windows PowerShell 5.1 or compatible PowerShell is required' }
    }
}

function Get-EnvironmentCallerContext {
    $identity = [string][Security.Principal.WindowsIdentity]::GetCurrent().Name
    $sandbox = $identity -match '(?i)codexsandbox' -or [bool]$env:CODEX_SANDBOX
    return [pscustomobject]@{
        Kind = if ($sandbox) { 'sandbox' } else { 'host' }
        IsSandbox = [bool]$sandbox
        ProcessArchitecture = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
    }
}

function Get-EnvironmentDockerProbe {
    param([switch]$RequireDaemon,[switch]$RequireSharedContainers)
    $probe = Get-ManagedDockerReadinessProbe
    $state = if (-not $RequireDaemon -and $probe.CliReady -and $probe.ContextReady) {
        'MATCH'
    } elseif ($probe.Classification) {
        [string]$probe.Classification
    } else {
        'MATCH'
    }
    $shared = $null
    if ($RequireDaemon -and $state -eq 'MATCH' -and $RequireSharedContainers) {
        $shared = Get-SharedInfrastructureStatus -Name @('main-postgres','main-redis') -SkipDockerReadiness
        if ($shared.Outcome -ne 'READY') { $state = [string]$shared.Classification }
    }
    $ready = $state -eq 'MATCH' -or $state -eq 'COMPATIBLE_DRIFT'
    $reason = if ($state -eq 'MATCH') { $null } elseif ($shared -and $shared.Reason) { $shared.Reason } elseif ($probe.Error) { $probe.Error } else { "Docker readiness classification is $state" }
    return [pscustomobject]@{
        State = $state; Ready = $ready; CliReady = [bool]$probe.CliReady
        DaemonReady = [bool]$probe.DaemonReady; ContextAvailable = [bool]$probe.ContextReady
        DaemonAccessDenied = [bool]$probe.CallerAccessDenied
        Classification = $state; ClientVersion = $probe.ClientVersion; ContextName = $probe.ContextName
        Shared = $shared; Reason = $reason
    }
}

function Test-EnvironmentHttpHealth {
    param([Parameter(Mandatory)][int]$Port)
    try {
        $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/actuator/health")
        $request.Timeout = 2000; $request.ReadWriteTimeout = 2000
        $response = $request.GetResponse()
        try { return [int]$response.StatusCode -eq 200 } finally { $response.Dispose() }
    } catch { return $false }
}

function Get-EnvironmentRuntimeProbe {
    $main = Test-EnvironmentHttpHealth -Port 8080
    $dispatcher = Test-EnvironmentHttpHealth -Port 8091
    return [pscustomobject]@{
        State = if ($main) { 'MATCH' } else { 'ACTION_REQUIRED' }; Ready = $main
        MainReady = $main; DispatcherReady = $dispatcher
        Reason = if ($main) { $null } else { 'main development runtime is not healthy for this caller' }
    }
}

function Get-EnvironmentGitHubProbe {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) { return [pscustomobject]@{ State = 'ACTION_REQUIRED'; Ready = $false; Authenticated = $false; Reason = 'GitHub CLI is not installed' } }
    $auth = Invoke-EnvironmentProcess -FilePath $gh.Source -Arguments @('auth', 'status', '--hostname', 'github.com') -TimeoutMilliseconds 5000
    return [pscustomobject]@{
        State = if ($auth.ExitCode -eq 0) { 'MATCH' } else { 'ACTION_REQUIRED' }
        Ready = $auth.ExitCode -eq 0; Authenticated = $auth.ExitCode -eq 0
        Reason = if ($auth.ExitCode -eq 0) { $null } else { 'GitHub CLI is not authenticated for github.com' }
    }
}

function Resolve-EnvironmentCapabilityState {
    param(
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [Parameter(Mandatory)]$Probes
    )
    $required = [Collections.Generic.List[object]]::new()
    $required.Add($Probes.PowerShell); $required.Add($Probes.Git)
    if ($Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E')) { $required.Add($Probes.Java) }
    if ($Capability -in @('DOCKER_TEST','DEV_RUNTIME','LINE_E2E')) { $required.Add($Probes.Docker) }
    if ($Capability -in @('DEV_RUNTIME','LINE_E2E')) { $required.Add($Probes.Runtime) }
    if ($Capability -eq 'EXTERNAL_PROVIDER') {
        if (-not $env:MMS_EXTERNAL_AUTHORITY_RECEIPT) {
            return [pscustomobject]@{ State = 'UNKNOWN'; Ready = $false; Reason = 'external authority receipt is required' }
        }
    }
    $blocked = @($required | Where-Object { -not $_.Ready })
    if ($blocked.Count -gt 0) {
        $knownClassifications = @(
            'DESKTOP_NOT_RUNNING','DAEMON_NOT_READY','CALLER_ACCESS_DENIED','AGENT_ASYNC_TOOL_REQUIRED',
            'SHARED_CONTAINER_STOPPED','SHARED_CONTAINER_IDENTITY_MISMATCH','SHARED_CONTAINER_UNHEALTHY',
            'WORKTREE_COMPOSE_NAME_CONFLICT','HOST_READY_CALLER_BLOCKED'
        )
        $classified = @($blocked | ForEach-Object {
                if ($_.PSObject.Properties['Classification'] -and $_.Classification -in $knownClassifications) {
                    [string]$_.Classification
                } elseif ($_.State -in $knownClassifications) {
                    [string]$_.State
                }
            } | Select-Object -First 1)
        $legacyCallerBlocked = @($blocked | Where-Object { $_.State -eq 'HOST_READY_CALLER_BLOCKED' }).Count -gt 0
        $callerBlocked = @($blocked | Where-Object {
                $classificationValue = if ($_.PSObject.Properties['Classification']) { [string]$_.Classification } else { $null }
                $_.State -eq 'CALLER_ACCESS_DENIED' -or $classificationValue -eq 'CALLER_ACCESS_DENIED'
            }).Count -gt 0
        $unknown = @($blocked | Where-Object { $_.State -eq 'UNKNOWN' }).Count -gt 0
        return [pscustomobject]@{
            State = if ($legacyCallerBlocked) { 'HOST_READY_CALLER_BLOCKED' } elseif ($callerBlocked) { 'CALLER_ACCESS_DENIED' } elseif ($classified.Count -gt 0) { $classified[0] } elseif ($unknown) { 'UNKNOWN' } else { 'ACTION_REQUIRED' }
            Ready = $false; Reason = (@($blocked | ForEach-Object { $_.Reason } | Where-Object { $_ }) -join '; ')
        }
    }
    $drift = @($required | Where-Object { $_.State -eq 'COMPATIBLE_DRIFT' }).Count -gt 0
    return [pscustomobject]@{ State = if ($drift) { 'COMPATIBLE_DRIFT' } else { 'MATCH' }; Ready = $true; Reason = $null }
}

function Read-EnvironmentJson {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try { return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw "Environment state is invalid: $Path" }
}

function Write-EnvironmentDemand {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [int]$TtlMinutes = 30
    )
    Write-CoordinationJsonAtomic -Path (Get-EnvironmentDemandPath -Context $Context -Capability $Capability) -Document ([ordered]@{
        schemaVersion = $script:EnvironmentSchemaVersion; capability = $Capability
        callerKind = $Context.CallerKind
        requestedAt = [datetime]::UtcNow.ToString('o'); expiresAt = [datetime]::UtcNow.AddMinutes($TtlMinutes).ToString('o')
    })
}

function Get-ActiveEnvironmentDemand {
    param([Parameter(Mandatory)]$Context)
    $rank = @{READ_ONLY=10;SOURCE_WRITE=20;MAVEN=30;DOCKER_TEST=40;DEV_RUNTIME=50;LINE_E2E=60;EXTERNAL_PROVIDER=70}
    $candidates = [Collections.Generic.List[object]]::new()
    if (Test-Path -LiteralPath $Context.DemandsPath -PathType Container) {
        foreach ($file in @(Get-ChildItem -LiteralPath $Context.DemandsPath -Filter 'demand-*.json' -File)) {
            $demand = Read-EnvironmentJson -Path $file.FullName
            if ($demand -and $rank.ContainsKey([string]$demand.capability) -and
                    [datetime]$demand.expiresAt -gt [datetime]::UtcNow) { $candidates.Add($demand) }
        }
    }
    if ($candidates.Count -eq 0) {
        $legacy = Read-EnvironmentJson -Path $Context.DemandPath
        if ($legacy -and $rank.ContainsKey([string]$legacy.capability) -and
                [datetime]$legacy.expiresAt -gt [datetime]::UtcNow) { $candidates.Add($legacy) }
    }
    $selected = $null
    foreach ($candidate in $candidates) {
        if (-not $selected -or $rank[[string]$candidate.capability] -gt $rank[[string]$selected.capability] -or
                ($rank[[string]$candidate.capability] -eq $rank[[string]$selected.capability] -and
                 [datetime]$candidate.requestedAt -gt [datetime]$selected.requestedAt)) { $selected = $candidate }
    }
    return $selected
}

function Test-EnvironmentSnapshotFresh {
    param(
        [AllowNull()]$Snapshot,
        [string]$Capability,
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
    )
    if (-not $Snapshot -or -not $Snapshot.expiresAt) { return $false }
    if (-not $Snapshot.PSObject.Properties['schemaVersion'] -or
            [int]$Snapshot.schemaVersion -ne $script:EnvironmentSchemaVersion) { return $false }
    $currentContract = Get-EnvironmentContract -RepoRoot $RepoRoot
    if (-not $Snapshot.PSObject.Properties['contract'] -or
            -not $Snapshot.contract.PSObject.Properties['Fingerprint'] -or
            $Snapshot.contract.Fingerprint -ne $currentContract.Fingerprint) { return $false }
    if ([datetime]$Snapshot.expiresAt -le [datetime]::UtcNow) { return $false }
    if ($Capability -and $Snapshot.requestedCapability -ne $Capability) { return $false }
    return $true
}

function New-EnvironmentSnapshot {
    param(
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [string]$StateRoot = (Get-EnvironmentDefaultRoot),
        [string]$MachineAlias,
        [hashtable]$ProbeOverrides,
        [switch]$NoWrite
    )
    $context = Get-EnvironmentStateContext -RepoRoot $RepoRoot -StateRoot $StateRoot -MachineAlias $MachineAlias
    $capabilitySnapshotPath = Get-EnvironmentSnapshotPath -Context $context -Capability $Capability
    $previous = Read-EnvironmentJson -Path $capabilitySnapshotPath
    $sequence = if ($previous -and $previous.sequence) { [long]$previous.sequence + 1 } else { 1 }
    $needsJava = $Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E')
    $needsDocker = $Capability -in @('DOCKER_TEST','DEV_RUNTIME','LINE_E2E')
    $needsRuntime = $Capability -in @('DEV_RUNTIME','LINE_E2E')
    $probes = [ordered]@{
        PowerShell = if ($ProbeOverrides -and $ProbeOverrides.ContainsKey('PowerShell')) { $ProbeOverrides.PowerShell } else { Get-EnvironmentPowerShellProbe }
        Git = if ($ProbeOverrides -and $ProbeOverrides.ContainsKey('Git')) { $ProbeOverrides.Git } else { Get-EnvironmentGitProbe -RepoRoot $RepoRoot }
        Java = if ($ProbeOverrides -and $ProbeOverrides.ContainsKey('Java')) { $ProbeOverrides.Java } elseif ($needsJava) { Get-EnvironmentJavaProbe -RepoRoot $RepoRoot } else { [pscustomobject]@{ State='UNKNOWN';Ready=$false;Reason='not probed for requested capability' } }
        Docker = if ($ProbeOverrides -and $ProbeOverrides.ContainsKey('Docker')) { $ProbeOverrides.Docker } elseif ($needsDocker) { Get-EnvironmentDockerProbe -RequireDaemon -RequireSharedContainers } else { [pscustomobject]@{ State='UNKNOWN';Ready=$false;Reason='not probed for requested capability' } }
        Runtime = if ($ProbeOverrides -and $ProbeOverrides.ContainsKey('Runtime')) { $ProbeOverrides.Runtime } elseif ($needsRuntime) { Get-EnvironmentRuntimeProbe } else { [pscustomobject]@{ State='UNKNOWN';Ready=$false;Reason='not probed for requested capability' } }
    }
    $capabilityState = Resolve-EnvironmentCapabilityState -Capability $Capability -Probes ([pscustomobject]$probes)
    $contract = Get-EnvironmentContract -RepoRoot $RepoRoot
    $snapshot = [ordered]@{
        schemaVersion = $script:EnvironmentSchemaVersion; repoId = $context.RepoId; machineAlias = $context.MachineAlias
        sequence = $sequence; observedAt = [datetime]::UtcNow.ToString('o')
        expiresAt = [datetime]::UtcNow.AddMinutes($contract.SnapshotTtlMinutes).ToString('o')
        requestedCapability = $Capability; caller = Get-EnvironmentCallerContext
        contract = $contract; capability = $capabilityState; probes = [pscustomobject]$probes
    }
    if (-not $NoWrite) {
        Write-CoordinationJsonAtomic -Path $capabilitySnapshotPath -Document $snapshot
        Write-CoordinationJsonAtomic -Path $context.SnapshotPath -Document $snapshot
    }
    return [pscustomobject]$snapshot
}

function Invoke-EnvironmentPreflight {
    param(
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [string]$StateRoot = (Get-EnvironmentDefaultRoot),
        [string]$MachineAlias,
        [switch]$RequireFresh,
        [switch]$ApplyProcessJava,
        [switch]$SkipDemandWrite
    )
    $context = Get-EnvironmentStateContext -RepoRoot $RepoRoot -StateRoot $StateRoot -MachineAlias $MachineAlias
    $capabilitySnapshotPath = Get-EnvironmentSnapshotPath -Context $context -Capability $Capability
    if (-not $SkipDemandWrite) { Write-EnvironmentDemand -Context $context -Capability $Capability }
    $cached = Read-EnvironmentJson -Path $capabilitySnapshotPath
    if (-not $RequireFresh -and (Test-EnvironmentSnapshotFresh -Snapshot $cached -Capability $Capability)) {
        if ($ApplyProcessJava -and $Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
                $cached.probes.Java.CandidateHome) {
            $env:JAVA_HOME = [string]$cached.probes.Java.CandidateHome
        }
        return $cached
    }
    $mutex = New-CoordinationMutex "environment-preflight/$($context.RepoId)/$($context.MachineAlias)"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $mutex -Deadline ([datetime]::UtcNow.AddSeconds(10))
        if (-not $held) {
            return [pscustomobject]@{ schemaVersion=$script:EnvironmentSchemaVersion;requestedCapability=$Capability;capability=[pscustomobject]@{State='UNKNOWN';Ready=$false;Reason='environment preflight is BUSY'} }
        }
        $cached = Read-EnvironmentJson -Path $capabilitySnapshotPath
        if (-not $RequireFresh -and (Test-EnvironmentSnapshotFresh -Snapshot $cached -Capability $Capability)) {
            if ($ApplyProcessJava -and $Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
                    $cached.probes.Java.CandidateHome) {
                $env:JAVA_HOME = [string]$cached.probes.Java.CandidateHome
            }
            return $cached
        }
        $snapshot = New-EnvironmentSnapshot -Capability $Capability -RepoRoot $RepoRoot -StateRoot $StateRoot -MachineAlias $MachineAlias
        if ($ApplyProcessJava -and $Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
                $snapshot.probes.Java.CandidateHome -and $snapshot.probes.Java.MavenReady -and
                -not $snapshot.probes.Java.Ready) {
            $persistentReady = $snapshot.probes.Java.PSObject.Properties['PersistentConfigurationReady'] -and
                [bool]$snapshot.probes.Java.PersistentConfigurationReady
            $javaIssueCode = if ($persistentReady) { 'JAVA_HOME_PROCESS_STALE' } else { 'JAVA_HOME_INVALID' }
            Write-EnvironmentIssue -Code $javaIssueCode -Capability $Capability `
                -Expected 'JAVA_HOME resolves to the verified Java 21 JDK used by Maven Wrapper' `
                -Actual ([string]$snapshot.probes.Java.Reason) -RecheckKind JAVA_HOME `
                -RepoRoot $RepoRoot -StateRoot $StateRoot -MachineAlias $MachineAlias | Out-Null
            $env:JAVA_HOME = [string]$snapshot.probes.Java.CandidateHome
            $candidateJava = Join-Path ([string]$snapshot.probes.Java.CandidateHome) 'bin\java.exe'
            $snapshot.probes.Java.ConfiguredHome = [string]$snapshot.probes.Java.CandidateHome
            $snapshot.probes.Java.ConfiguredJava = if ($snapshot.probes.Java.UserJava.Path -and
                    [string]::Equals([string]$snapshot.probes.Java.UserJava.Path, $candidateJava, [StringComparison]::OrdinalIgnoreCase)) {
                $snapshot.probes.Java.UserJava
            } else { $snapshot.probes.Java.PathJava }
            $snapshot.probes.Java.State = if ($snapshot.probes.Java.PathJava.Ready -and
                    $snapshot.probes.Java.PathJava.Major -eq 21 -and
                    -not [string]::Equals([string]$snapshot.probes.Java.PathJava.Path, $candidateJava, [StringComparison]::OrdinalIgnoreCase)) {
                'COMPATIBLE_DRIFT'
            } else { 'MATCH' }
            $snapshot.probes.Java.Ready = $true
            $snapshot.probes.Java.Reason = $null
            $snapshot.probes.Java | Add-Member -NotePropertyName ProcessRepairApplied -NotePropertyValue $true -Force
            $snapshot.probes.Java | Add-Member -NotePropertyName ProcessConfigurationState -NotePropertyValue 'REPAIRED_FOR_CURRENT_PROCESS' -Force
            $snapshot.probes.Java | Add-Member -NotePropertyName PersistentRepairEligible `
                -NotePropertyValue (-not (Get-EnvironmentCallerContext).IsSandbox -and -not $persistentReady) -Force
            $snapshot.capability = Resolve-EnvironmentCapabilityState -Capability $Capability -Probes $snapshot.probes
            $snapshot.observedAt = [datetime]::UtcNow.ToString('o')
            $snapshot.expiresAt = [datetime]::UtcNow.AddMinutes((Get-EnvironmentContract -RepoRoot $RepoRoot).SnapshotTtlMinutes).ToString('o')
            Write-CoordinationJsonAtomic -Path $capabilitySnapshotPath -Document $snapshot
            Write-CoordinationJsonAtomic -Path $context.SnapshotPath -Document $snapshot
        } elseif ($ApplyProcessJava -and $Capability -in @('MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E') -and
                $snapshot.probes.Java.CandidateHome) {
            $env:JAVA_HOME = [string]$snapshot.probes.Java.CandidateHome
        }
        $receipt = [ordered]@{
            schemaVersion=$script:EnvironmentSchemaVersion;operationId="preflight-$([guid]::NewGuid())"
            outcome=if($snapshot.capability.Ready){'READY'}else{'BLOCKED'};capability=$Capability
            machineAlias=$context.MachineAlias;observedAt=$snapshot.observedAt;snapshotSequence=$snapshot.sequence
            state=$snapshot.capability.State;contractFingerprint=$snapshot.contract.Fingerprint
        }
        Write-CoordinationJsonAtomic -Path (Join-Path $context.ReceiptsPath "$($receipt.operationId).json") -Document $receipt
        return $snapshot
    } finally {
        if ($held) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

function ConvertTo-PublishedEnvironmentSnapshot {
    param([Parameter(Mandatory)]$Snapshot)
    return [ordered]@{
        schemaVersion = $Snapshot.schemaVersion; machineAlias = $Snapshot.machineAlias; sequence = $Snapshot.sequence
        observedAt = $Snapshot.observedAt; expiresAt = [datetime]::UtcNow.AddMinutes(30).ToString('o')
        requestedCapability = $Snapshot.requestedCapability; callerKind = $Snapshot.caller.Kind
        capability = [ordered]@{ state=$Snapshot.capability.State; ready=[bool]$Snapshot.capability.Ready; reason=$Snapshot.capability.Reason }
        contractFingerprint = $Snapshot.contract.Fingerprint
        tools = [ordered]@{
            git = $Snapshot.probes.Git.Version
            javaState = $Snapshot.probes.Java.State
            javaProcessState = if ($Snapshot.probes.Java.PSObject.Properties['ProcessConfigurationState']) { $Snapshot.probes.Java.ProcessConfigurationState } else { $Snapshot.probes.Java.State }
            dockerState = $Snapshot.probes.Docker.State
            runtimeState = $Snapshot.probes.Runtime.State
        }
    }
}

function Get-EnvironmentIssueFingerprint {
    param([Parameter(Mandatory)][string]$Code, [Parameter(Mandatory)][string]$Capability, [string]$CallerKind)
    return (Get-CoordinationHash ("$Code|$Capability|$CallerKind".ToLowerInvariant())).Substring(0, 24)
}

function Write-EnvironmentIssue {
    param(
        [Parameter(Mandatory)][string]$Code,
        [Parameter(Mandatory)][ValidateSet('READ_ONLY','SOURCE_WRITE','MAVEN','DOCKER_TEST','DEV_RUNTIME','LINE_E2E','EXTERNAL_PROVIDER')][string]$Capability,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Actual,
        [ValidateSet('MANUAL','CAPABILITY','JAVA_HOME')][string]$RecheckKind = 'MANUAL',
        [ValidateSet('OPEN','ACCEPTED_LIMITATION')][string]$Status = 'OPEN',
        [string]$ResolutionEvidence,
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [string]$StateRoot = (Get-EnvironmentDefaultRoot),
        [string]$MachineAlias
    )
    $context = Get-EnvironmentStateContext -RepoRoot $RepoRoot -StateRoot $StateRoot -MachineAlias $MachineAlias
    $caller = Get-EnvironmentCallerContext
    $fingerprint = Get-EnvironmentIssueFingerprint -Code $Code -Capability $Capability -CallerKind $caller.Kind
    $path = Join-Path $context.IssuesPath "$fingerprint.json"
    $existing = Read-EnvironmentJson -Path $path
    $now = [datetime]::UtcNow.ToString('o')
    $contract = Get-EnvironmentContract -RepoRoot $RepoRoot
    $effectiveStatus = if ($existing -and $existing.status -eq 'ACCEPTED_LIMITATION' -and $Status -eq 'OPEN') {
        'ACCEPTED_LIMITATION'
    } else { $Status }
    $effectiveResolution = if (-not [string]::IsNullOrWhiteSpace($ResolutionEvidence)) {
        $ResolutionEvidence
    } elseif ($existing -and $existing.PSObject.Properties['resolutionEvidence']) {
        $existing.resolutionEvidence
    } else { $null }
    $issue = [ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;fingerprint=$fingerprint;code=$Code;capability=$Capability
        callerKind=$caller.Kind;firstSeen=if($existing){$existing.firstSeen}else{$now};lastSeen=$now
        occurrences=if($existing){[int]$existing.occurrences+1}else{1};expected=$Expected;actual=$Actual
        contractFingerprint=$contract.Fingerprint;recheckKind=$RecheckKind;status=$effectiveStatus
        resolutionEvidence=$effectiveResolution
    }
    Write-CoordinationJsonAtomic -Path $path -Document $issue
    return [pscustomobject]$issue
}

function Assert-EnvironmentReviewDocument {
    param(
        [Parameter(Mandatory)]$Review,
        [Parameter(Mandatory)][string]$ReleaseGate,
        [Parameter(Mandatory)][ValidateSet('Manual','Automatic')][string]$Mode,
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [string]$TargetWorktree,
        [string]$AnchorWorktree,
        [string]$EvidenceRoot,
        [string]$StateRoot,
        [string]$MachineAlias,
        [string[]]$RequiredCapability = @(),
        [ValidateRange(1,168)][int]$MaximumAgeHours = 48
    )
    if ([int]$Review.schemaVersion -ne $script:EnvironmentSchemaVersion) { throw 'Environment review schema version is unsupported.' }
    if ($Review.releaseGate -ne $ReleaseGate) { throw "Environment review gate mismatch: expected $ReleaseGate, got $($Review.releaseGate)" }
    if ($Review.mode -ne $Mode) { throw "Environment review mode mismatch: expected $Mode, got $($Review.mode)" }
    if ($MachineAlias -and $Review.machineAlias -ne $MachineAlias) {
        throw "Environment review machine mismatch: expected $MachineAlias, got $($Review.machineAlias)"
    }
    $target = if ($TargetWorktree) { $TargetWorktree } else { $RepoRoot }
    $anchor = if ($AnchorWorktree) { $AnchorWorktree } else { $RepoRoot }
    $targetIdentity = Assert-EnvironmentTargetWorktree -TargetWorktree $target -AnchorWorktree $anchor
    if (-not $Review.PSObject.Properties['targetWorktree'] -or
            -not [bool]$Review.targetWorktree.registered -or
            $Review.targetWorktree.worktreeId -ne $targetIdentity.WorktreeId -or
            $Review.targetWorktree.repoId -ne $targetIdentity.RepoId -or
            -not [string]::Equals([string]$Review.targetWorktree.path,$targetIdentity.Path,[StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals([string]$Review.targetWorktree.commonDirectory,$targetIdentity.CommonDirectory,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Environment review target worktree identity does not match the registered target.'
    }
    if ($StateRoot -and $MachineAlias) { Assert-EnvironmentMachineAlias -StateRoot $StateRoot -MachineAlias $MachineAlias | Out-Null }
    $allowed = [IO.Path]::GetFullPath((Join-Path $targetIdentity.Path 'docs\exec-plans\evidence\development-environment')).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    $allowedRoot = $allowed.TrimEnd('\','/')
    if ($EvidenceRoot) {
        $resolvedRoot = [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedRoot.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Environment review evidence root escaped the target worktree.' }
    }
    $reviewEvidencePaths = @()
    if ($Review.PSObject.Properties['evidenceRoot']) { $reviewEvidencePaths += [string]$Review.evidenceRoot }
    if ($Review.PSObject.Properties['evidencePath']) { $reviewEvidencePaths += [string]$Review.evidencePath }
    foreach ($reviewEvidencePath in @($reviewEvidencePaths | Where-Object { $_ })) {
        $resolvedReviewPath = [IO.Path]::GetFullPath($reviewEvidencePath)
        if (-not [string]::Equals($resolvedReviewPath,$allowedRoot,[StringComparison]::OrdinalIgnoreCase) -and
            -not $resolvedReviewPath.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'Environment review evidence escaped the target worktree approved directory.'
        }
    }
    if ($Review.outcome -ne 'PASS' -or [int]$Review.openCount -ne 0) { throw 'Environment review contains open blockers and cannot release.' }
    if ([datetime]$Review.reviewedAt -lt [datetime]::UtcNow.AddHours(-$MaximumAgeHours)) { throw 'Environment review evidence is stale.' }
    $contract = Get-EnvironmentContract -RepoRoot $RepoRoot
    if ($Review.contractFingerprint -ne $contract.Fingerprint) { throw 'Environment review contract is stale after tooling evolution.' }
    $requiredNames = [Collections.Generic.List[string]]::new()
    foreach ($entry in @($RequiredCapability)) {
        foreach ($name in @([string]$entry -split ',')) {
            $normalized = $name.Trim().ToUpperInvariant()
            if ($normalized -and -not $requiredNames.Contains($normalized)) { $requiredNames.Add($normalized) }
        }
    }
    foreach ($requiredName in $requiredNames) {
        $matching = @($Review.capabilities | Where-Object { $_.name -eq $requiredName -and [bool]$_.ready })
        if ($matching.Count -eq 0) { throw "Environment review lacks ready required capability: $requiredName" }
    }
    return [pscustomobject]@{
        status='passed';releaseGate=$ReleaseGate;mode=$Mode;reviewedAt=$Review.reviewedAt
        machineAlias=$Review.machineAlias;requiredCapabilities=@($requiredNames);contractFingerprint=$contract.Fingerprint
    }
}

Set-StrictMode -Version Latest

$script:ManagedDockerDesktopPath = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$script:ManagedDockerCliPath = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$script:ManagedDockerProjectRoot = 'D:\my-project\my-mobile-secretary'
$script:ManagedDockerCapability = 'DOCKER_TEST'

function Resolve-ManagedDockerFileIdentity {
    param(
        [Parameter(Mandatory)][string]$ExpectedPath,
        [Parameter(Mandatory)][string]$Label
    )
    $expected = [IO.Path]::GetFullPath($ExpectedPath).TrimEnd('\')
    if (-not (Test-Path -LiteralPath $expected -PathType Leaf)) {
        throw "$Label is not a regular file at the allowlisted path."
    }
    $item = Get-Item -LiteralPath $expected -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label allowlisted path is a reparse point; launch refused."
    }
    if ($item.Length -le 0) { throw "$Label allowlisted file is empty; launch refused." }
    $signature = Get-AuthenticodeSignature -LiteralPath $expected -ErrorAction Stop
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(?i)CN=Docker Inc') {
        throw "$Label is not signed by Docker Inc with a valid Authenticode signature."
    }
    $hash = (Get-FileHash -LiteralPath $expected -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    return [pscustomobject]@{
        Path = $expected
        Directory = Split-Path -Parent $expected
        Sha256 = $hash
        Length = [long]$item.Length
        SignerSubject = [string]$signature.SignerCertificate.Subject
    }
}

function Resolve-ManagedDockerDesktopIdentity {
    return Resolve-ManagedDockerFileIdentity -ExpectedPath $script:ManagedDockerDesktopPath -Label 'Docker Desktop'
}

function Resolve-ManagedDockerCliIdentity {
    $expected = [IO.Path]::GetFullPath($script:ManagedDockerCliPath)
    $commands = @(Get-Command docker -CommandType Application -ErrorAction SilentlyContinue)
    $matching = @($commands | Where-Object {
        try { [string]::Equals([IO.Path]::GetFullPath([string]$_.Source), $expected, [StringComparison]::OrdinalIgnoreCase) }
        catch { $false }
    })
    if ($matching.Count -eq 0) {
        throw 'Docker CLI is unavailable at the allowlisted Docker installation path.'
    }
    if ($matching.Count -gt 1) {
        throw 'Docker CLI has multiple matching allowlisted command entries; launch refused.'
    }
    return Resolve-ManagedDockerFileIdentity -ExpectedPath $expected -Label 'Docker CLI'
}

function Get-ManagedDockerDesktopProcesses {
    param([Parameter(Mandatory)]$DesktopIdentity)
    try {
        $processes = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'Docker Desktop.exe'" -ErrorAction Stop)
    } catch {
        throw "Docker Desktop process identity could not be inspected; launch refused. $($_.Exception.Message)"
    }
    $allowedProcessPaths = [Collections.Generic.List[string]]::new()
    $allowedProcessPaths.Add($DesktopIdentity.Path) | Out-Null
    $frontendPath = Join-Path $DesktopIdentity.Directory 'frontend\Docker Desktop.exe'
    if (Test-Path -LiteralPath $frontendPath -PathType Leaf) {
        try {
            $frontend = Resolve-ManagedDockerFileIdentity -ExpectedPath $frontendPath -Label 'Docker Desktop frontend'
            $allowedProcessPaths.Add($frontend.Path) | Out-Null
        } catch { }
    }
    $matching = @($processes | ForEach-Object {
        $processPath = [string]$_.ExecutablePath
        if ($processPath -and @($allowedProcessPaths | Where-Object {
            [string]::Equals([IO.Path]::GetFullPath($processPath), $_, [StringComparison]::OrdinalIgnoreCase)
        }).Count -eq 1) { $_ }
    })
    $matchingIds = @($matching | ForEach-Object { [int]$_.ProcessId })
    $mismatched = @($processes | Where-Object {
        -not $_.ExecutablePath -or $matchingIds -notcontains ([int]$_.ProcessId)
    })
    return [pscustomobject]@{
        Matching = $matching
        Mismatched = $mismatched
        Count = $matching.Count
        MismatchCount = $mismatched.Count
    }
}

function Invoke-ManagedDockerCli {
    param(
        [Parameter(Mandatory)]$CliIdentity,
        [Parameter(Mandatory)][string[]]$Arguments,
        [int]$TimeoutMilliseconds = 5000
    )
    $quoted = @($Arguments | ForEach-Object {
        $value = [string]$_
        if ($value -notmatch '[\s"]') { return $value }
        return '"' + ($value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
    })
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $CliIdentity.Path
    $startInfo.Arguments = ($quoted -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Docker CLI process could not be started.' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $completed = $process.WaitForExit($TimeoutMilliseconds)
    if (-not $completed) {
        try { $process.Kill() } catch { }
        return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = 'Docker CLI command timed out.'; TimedOut = $true; Arguments = @($Arguments) }
    }
    $process.WaitForExit()
    $exitCode = [int]$process.ExitCode
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $stdout.Result.Trim()
        Error = $stderr.Result.Trim()
        TimedOut = $false
        Arguments = @($Arguments)
    }
}

function Test-ManagedDockerDaemonReady {
    param([Parameter(Mandatory)]$CliIdentity)
    $context = Invoke-ManagedDockerCli -CliIdentity $CliIdentity -Arguments @('context', 'show')
    $info = Invoke-ManagedDockerCli -CliIdentity $CliIdentity -Arguments @('info', '--format', '{{.ServerVersion}}')
    $representative = Invoke-ManagedDockerCli -CliIdentity $CliIdentity -Arguments @('ps', '--format', '{{.ID}}')
    $ready = $context.ExitCode -eq 0 -and
        -not [string]::IsNullOrWhiteSpace($context.Output) -and
        $info.ExitCode -eq 0 -and
        -not [string]::IsNullOrWhiteSpace($info.Output) -and
        $representative.ExitCode -eq 0
    return [pscustomobject]@{
        Ready = [bool]$ready
        Context = if ($context.ExitCode -eq 0) { $context.Output } else { $null }
        ServerVersion = if ($info.ExitCode -eq 0) { $info.Output } else { $null }
        RepresentativeCommand = 'docker ps --format {{.ID}}'
        Reason = if ($ready) { $null } else { 'Docker context, daemon info, or representative docker command failed.' }
    }
}

function Resolve-ManagedDockerStateRoot {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [AllowNull()][string]$StateRoot
    )
    $primary = [IO.Path]::GetFullPath($script:ManagedDockerProjectRoot).TrimEnd('\')
    $repo = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
    $isPrimary = [string]::Equals($repo, $primary, [StringComparison]::OrdinalIgnoreCase)
    $isManagedWorktree = $repo.StartsWith("$primary\var\worktrees\", [StringComparison]::OrdinalIgnoreCase)
    if (-not $isPrimary -and -not $isManagedWorktree) {
        throw 'Managed Docker entrypoint is limited to this repository or var/worktrees descendants.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $repo 'scripts') -PathType Container)) {
        throw 'Managed Docker entrypoint must run from a repository-owned scripts directory.'
    }
    $allowed = [IO.Path]::GetFullPath((Join-Path $repo 'var\environment-state\v1')).TrimEnd('\')
    if ([string]::IsNullOrWhiteSpace($StateRoot)) { return $allowed }
    $candidate = [IO.Path]::GetFullPath($StateRoot).TrimEnd('\')
    if (-not [string]::Equals($candidate, $allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Managed Docker entrypoint may only use the repository environment-state/v1 root.'
    }
    return $allowed
}

function Get-ManagedDockerMachineAlias {
    $alias = if ($env:MMS_MACHINE_ALIAS) { [string]$env:MMS_MACHINE_ALIAS } else { 'local' }
    if ($alias -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$') { return 'local' }
    return $alias
}

function Write-ManagedDockerReceipt {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)]$Receipt
    )
    [IO.Directory]::CreateDirectory((Join-Path $StateRoot 'receipts')) | Out-Null
    Write-CoordinationJsonAtomic -Path (Join-Path (Join-Path $StateRoot 'receipts') "$($Receipt.operationId).json") -Document $Receipt
    Write-CoordinationReceipt -StateRoot (Get-CoordinationDefaultRoot) -Receipt $Receipt
}

function New-ManagedDockerReceipt {
    param(
        [Parameter(Mandatory)][string]$OperationId,
        [Parameter(Mandatory)][datetime]$ObservedAt,
        [Parameter(Mandatory)][string]$Outcome,
        [Parameter(Mandatory)][string]$LaunchDisposition,
        [Parameter(Mandatory)][string]$FailureClassification,
        [Parameter(Mandatory)][bool]$DaemonReady,
        [Parameter(Mandatory)][string]$StateRoot,
        [AllowNull()]$DesktopIdentity,
        [AllowNull()]$CliIdentity,
        [AllowNull()]$DaemonProbe,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    $receipt = [ordered]@{
        schemaVersion = 1
        operationId = $OperationId
        requestedCapability = $script:ManagedDockerCapability
        outcome = $Outcome
        launchDisposition = $LaunchDisposition
        daemonReady = $DaemonReady
        failureClassification = $FailureClassification
        timeoutSeconds = $TimeoutSeconds
        executablePath = if ($DesktopIdentity) { $DesktopIdentity.Path } else { $script:ManagedDockerDesktopPath }
        executableSha256 = if ($DesktopIdentity) { $DesktopIdentity.Sha256 } else { $null }
        executableSigner = if ($DesktopIdentity) { $DesktopIdentity.SignerSubject } else { $null }
        cliPath = if ($CliIdentity) { $CliIdentity.Path } else { $script:ManagedDockerCliPath }
        cliSha256 = if ($CliIdentity) { $CliIdentity.Sha256 } else { $null }
        cliSigner = if ($CliIdentity) { $CliIdentity.SignerSubject } else { $null }
        dockerContext = if ($DaemonProbe) { $DaemonProbe.Context } else { $null }
        serverVersion = if ($DaemonProbe) { $DaemonProbe.ServerVersion } else { $null }
        representativeCommand = 'docker ps --format {{.ID}}'
        machineAlias = Get-ManagedDockerMachineAlias
        observedAt = $ObservedAt.ToUniversalTime().ToString('o')
        stateRoot = $StateRoot
    }
    return [pscustomobject]$receipt
}

function Invoke-ManagedDockerDesktop {
    [CmdletBinding()]
    param(
        [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
        [ValidateRange(1, 900)][int]$TimeoutSeconds = 180,
        [AllowNull()][string]$StateRoot
    )
    $operationId = "docker-desktop-$([guid]::NewGuid().ToString())"
    $observedAt = [datetime]::UtcNow
    $resolvedStateRoot = $null
    $desktop = $null
    $cli = $null
    $probe = $null
    $outcome = 'FAILED'
    $disposition = 'not-started'
    $classification = 'UNCLASSIFIED_FAILURE'
    $daemonReady = $false
    $operation = $null
    try {
        $resolvedStateRoot = Resolve-ManagedDockerStateRoot -RepoRoot $RepoRoot -StateRoot $StateRoot
        $desktop = Resolve-ManagedDockerDesktopIdentity
        $cli = Resolve-ManagedDockerCliIdentity
        $resource = New-CoordinationResource -Type 'machine/docker-daemon' -Key 'docker-desktop' -Mode Exclusive
        $operation = Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds $TimeoutSeconds `
            -OperationId $operationId -StateRoot (Get-CoordinationDefaultRoot)
        if ($operation.Outcome -ne 'READY') {
            $classification = 'COORDINATION_BUSY'
        } else {
            $processes = Get-ManagedDockerDesktopProcesses -DesktopIdentity $desktop
            if ($processes.MismatchCount -gt 0) {
                $classification = 'EXECUTABLE_IDENTITY_MISMATCH'
            } else {
                $probe = Test-ManagedDockerDaemonReady -CliIdentity $cli
                if ($probe.Ready) {
                    $outcome = 'READY'; $disposition = 'already-running'; $classification = 'NONE'; $daemonReady = $true
                } else {
                    if ($processes.Count -eq 0) {
                        Start-Process -FilePath $desktop.Path -WorkingDirectory $desktop.Directory -WindowStyle Hidden | Out-Null
                        $disposition = 'started'
                    } else {
                        $disposition = 'already-running'
                    }
                    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
                    while ([datetime]::UtcNow -lt $deadline) {
                        $probe = Test-ManagedDockerDaemonReady -CliIdentity $cli
                        if ($probe.Ready) {
                            $outcome = 'READY'; $classification = 'NONE'; $daemonReady = $true
                            break
                        }
                        Start-Sleep -Seconds 2
                    }
                    if (-not $daemonReady) { $classification = 'DAEMON_TIMEOUT' }
                }
            }
        }
    } catch {
        $classification = if ($_.Exception.Message -match '(?i)allowlisted|regular file|reparse|resolved outside') {
            'EXECUTABLE_IDENTITY_INVALID'
        } elseif ($_.Exception.Message -match '(?i)coordination|mutex|operation') {
            'COORDINATION_UNAVAILABLE'
        } else { 'ACTION_REQUIRED' }
    } finally {
        if ($operation -and $operation.Outcome -eq 'READY') {
            try { Exit-CoordinationOperation -Operation $operation } catch { $classification = 'COORDINATION_RELEASE_FAILED'; $outcome = 'FAILED'; $daemonReady = $false }
        }
    }
    if (-not $resolvedStateRoot) { $resolvedStateRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'var\environment-state\v1' }
    if ($outcome -ne 'READY') { $daemonReady = $false; if ($classification -eq 'UNCLASSIFIED_FAILURE') { $classification = 'ACTION_REQUIRED' } }
    $receipt = New-ManagedDockerReceipt -OperationId $operationId -ObservedAt $observedAt `
        -Outcome $outcome -LaunchDisposition $disposition -FailureClassification $classification `
        -DaemonReady $daemonReady -StateRoot $resolvedStateRoot -DesktopIdentity $desktop `
        -CliIdentity $cli -DaemonProbe $probe -TimeoutSeconds $TimeoutSeconds
    try { Write-ManagedDockerReceipt -StateRoot $resolvedStateRoot -Receipt $receipt } catch {
        $receipt.outcome = 'FAILED'; $receipt.daemonReady = $false; $receipt.failureClassification = 'RECEIPT_WRITE_FAILED'
        $receipt | Add-Member -NotePropertyName receiptError -NotePropertyValue $_.Exception.Message -Force
    }
    return $receipt
}

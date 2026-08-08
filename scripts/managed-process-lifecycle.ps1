Set-StrictMode -Version Latest

if (-not (Get-Command Write-CoordinationJsonAtomic -ErrorAction SilentlyContinue)) {
    . "$PSScriptRoot\coordination-common.ps1"
}

function Get-ManagedComponentDefinition {
    param(
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][string]$Worktree
    )
    $normalized = [IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant()
    switch ($Component) {
        'SpringBoot' {
            return [pscustomobject]@{
                Component=$Component; StateField='springBootPid'; ReceiptField='springBootOwnershipReceiptId'
                Resource="v1/worktree/maven-target/root/$normalized"; Port=8080; Label='Spring Boot'
            }
        }
        'Dispatcher' {
            return [pscustomobject]@{
                Component=$Component; StateField='dispatcherPid'; ReceiptField='dispatcherOwnershipReceiptId'
                Resource="v1/worktree/maven-target/dispatcher/$normalized"; Port=8091; Label='AI Dispatcher'
            }
        }
        default {
            return [pscustomobject]@{
                Component=$Component; StateField='ngrokPid'; ReceiptField='ngrokOwnershipReceiptId'
                Resource="v1/worktree/managed-process/ngrok/$normalized"; Port=4040; Label='ngrok'
            }
        }
    }
}

function Get-WmiManagedProcessQueryResult {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [ValidateRange(100,3000)][int]$TimeoutMilliseconds = 3000
    )
    $searcher = $null
    $items = @()
    try {
        $query = "SELECT ProcessId,ParentProcessId,Name,ExecutablePath,CommandLine,CreationDate FROM Win32_Process WHERE ProcessId=$ProcessId"
        $searcher = [System.Management.ManagementObjectSearcher]::new($query)
        $searcher.Options.Timeout = [TimeSpan]::FromMilliseconds($TimeoutMilliseconds)
        $items = @($searcher.Get())
        if ($items.Count -eq 0) { return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null} }
        $item = $items[0]
        $startedAt = [Management.ManagementDateTimeConverter]::ToDateTime([string]$item.CreationDate).ToUniversalTime()
        return [pscustomobject]@{
            Outcome='READY'
            Snapshot=[pscustomobject]@{
                ProcessId=[int]$item.ProcessId; ParentProcessId=[int]$item.ParentProcessId
                Name=[string]$item.Name; ExecutablePath=[string]$item.ExecutablePath
                CommandLine=[string]$item.CommandLine; StartedAtUtc=$startedAt;QueryKind='WMI_EXACT'
            }
        }
    } catch {
        return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='WMI_QUERY_UNAVAILABLE'}
    } finally {
        foreach ($item in $items) { if ($item -is [IDisposable]) { $item.Dispose() } }
        if ($searcher) { $searcher.Dispose() }
    }
}

function Initialize-NativeManagedProcessIdentityType {
    if ('Mms.Tooling.NativeProcessIdentity' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace Mms.Tooling {
    public sealed class NativeProcessSnapshot {
        public int ProcessId { get; set; }
        public string ExecutablePath { get; set; }
        public string CommandLine { get; set; }
        public DateTime StartedAtUtc { get; set; }
    }

    public static class NativeProcessIdentity {
        private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        private const int ProcessCommandLineInformation = 60;

        [StructLayout(LayoutKind.Sequential)]
        private struct FILETIME {
            public uint Low;
            public uint High;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct UNICODE_STRING {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder path, ref int size);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetProcessTimes(IntPtr process, out FILETIME creation, out FILETIME exit, out FILETIME kernel, out FILETIME user);

        [DllImport("ntdll.dll")]
        private static extern int NtQueryInformationProcess(IntPtr process, int informationClass, IntPtr information, int informationLength, out int returnLength);

        public static NativeProcessSnapshot Query(int processId) {
            IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
            if (process == IntPtr.Zero) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
            IntPtr buffer = IntPtr.Zero;
            try {
                StringBuilder image = new StringBuilder(32768);
                int imageLength = image.Capacity;
                if (!QueryFullProcessImageName(process, 0, image, ref imageLength)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }

                FILETIME creation;
                FILETIME exit;
                FILETIME kernel;
                FILETIME user;
                if (!GetProcessTimes(process, out creation, out exit, out kernel, out user)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                long creationTicks = ((long)creation.High << 32) | creation.Low;

                int required;
                NtQueryInformationProcess(process, ProcessCommandLineInformation, IntPtr.Zero, 0, out required);
                if (required <= 0 || required > 1048576) {
                    throw new InvalidOperationException("Native command line size is invalid.");
                }
                buffer = Marshal.AllocHGlobal(required);
                int status = NtQueryInformationProcess(process, ProcessCommandLineInformation, buffer, required, out required);
                if (status < 0) { throw new InvalidOperationException("Native command line query failed."); }
                UNICODE_STRING command = (UNICODE_STRING)Marshal.PtrToStructure(buffer, typeof(UNICODE_STRING));
                if (command.Buffer == IntPtr.Zero || command.Length == 0 || command.Length > command.MaximumLength) {
                    throw new InvalidOperationException("Native command line identity is incomplete.");
                }

                return new NativeProcessSnapshot {
                    ProcessId = processId,
                    ExecutablePath = image.ToString(),
                    CommandLine = Marshal.PtrToStringUni(command.Buffer, command.Length / 2),
                    StartedAtUtc = DateTime.FromFileTimeUtc(creationTicks)
                };
            } finally {
                if (buffer != IntPtr.Zero) { Marshal.FreeHGlobal(buffer); }
                CloseHandle(process);
            }
        }
    }
}
'@
}

function Get-NativeManagedProcessQueryResult {
    param([Parameter(Mandatory)][int]$ProcessId)
    if ($env:OS -ne 'Windows_NT') {
        return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='NATIVE_PLATFORM_UNSUPPORTED'}
    }
    try {
        Initialize-NativeManagedProcessIdentityType
        $native = [Mms.Tooling.NativeProcessIdentity]::Query($ProcessId)
        return [pscustomobject]@{
            Outcome='READY'
            Snapshot=[pscustomobject]@{
                ProcessId=[int]$native.ProcessId;ParentProcessId=0
                Name=[IO.Path]::GetFileName([string]$native.ExecutablePath)
                ExecutablePath=[string]$native.ExecutablePath;CommandLine=[string]$native.CommandLine
                StartedAtUtc=([datetime]$native.StartedAtUtc).ToUniversalTime();QueryKind='NATIVE_EXACT'
            }
        }
    } catch {
        $process = $null
        try {
            $process = [Diagnostics.Process]::GetProcessById($ProcessId)
            return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='NATIVE_EXACT_QUERY_UNAVAILABLE'}
        } catch [ArgumentException] {
            return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
        } catch {
            return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='NATIVE_EXACT_QUERY_UNAVAILABLE'}
        } finally {
            if ($process) { $process.Dispose() }
        }
    }
}

function Test-ManagedProcessSnapshotComplete {
    param([AllowNull()]$Snapshot)
    return $null -ne $Snapshot -and [int]$Snapshot.ProcessId -gt 0 -and
        -not [string]::IsNullOrWhiteSpace([string]$Snapshot.Name) -and
        -not [string]::IsNullOrWhiteSpace([string]$Snapshot.ExecutablePath) -and
        -not [string]::IsNullOrWhiteSpace([string]$Snapshot.CommandLine) -and $null -ne $Snapshot.StartedAtUtc
}

function Copy-ManagedProcessSnapshot {
    param([Parameter(Mandatory)]$Snapshot, [Parameter(Mandatory)][string]$QueryKind)
    return [pscustomobject]@{
        ProcessId=[int]$Snapshot.ProcessId;ParentProcessId=[int]$Snapshot.ParentProcessId
        Name=[string]$Snapshot.Name;ExecutablePath=[string]$Snapshot.ExecutablePath
        CommandLine=[string]$Snapshot.CommandLine;StartedAtUtc=([datetime]$Snapshot.StartedAtUtc).ToUniversalTime()
        QueryKind=$QueryKind
    }
}

function Test-ManagedProcessSnapshotConsensus {
    param([Parameter(Mandatory)]$Left, [Parameter(Mandatory)]$Right)
    if ([int]$Left.ProcessId -ne [int]$Right.ProcessId) { return $false }
    if (-not [string]::Equals([IO.Path]::GetFullPath([string]$Left.ExecutablePath),
            [IO.Path]::GetFullPath([string]$Right.ExecutablePath), [StringComparison]::OrdinalIgnoreCase)) { return $false }
    if (-not [string]::Equals(([string]$Left.CommandLine).Trim(), ([string]$Right.CommandLine).Trim(),
            [StringComparison]::Ordinal)) { return $false }
    $leftStart = [datetimeoffset]$Left.StartedAtUtc
    $rightStart = [datetimeoffset]$Right.StartedAtUtc
    return [Math]::Abs(($leftStart.ToUniversalTime() - $rightStart.ToUniversalTime()).TotalSeconds) -le 1
}

function Get-ManagedProcessQueryResult {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [ValidateRange(100,3000)][int]$TimeoutMilliseconds = 3000,
        [scriptblock]$WmiQuery = { param($id,$timeout) Get-WmiManagedProcessQueryResult -ProcessId $id -TimeoutMilliseconds $timeout },
        [scriptblock]$NativeQuery = { param($id) Get-NativeManagedProcessQueryResult -ProcessId $id }
    )
    $wmi = & $WmiQuery $ProcessId $TimeoutMilliseconds
    $native = & $NativeQuery $ProcessId
    $wmiExact = $wmi -and $wmi.Outcome -eq 'READY' -and (Test-ManagedProcessSnapshotComplete $wmi.Snapshot)
    $nativeExact = $native -and $native.Outcome -eq 'READY' -and (Test-ManagedProcessSnapshotComplete $native.Snapshot)

    if ($wmiExact) {
        if ($nativeExact) {
            if (-not (Test-ManagedProcessSnapshotConsensus -Left $wmi.Snapshot -Right $native.Snapshot)) {
                return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_SOURCE_MISMATCH'}
            }
            return [pscustomobject]@{Outcome='READY';Snapshot=(Copy-ManagedProcessSnapshot $wmi.Snapshot 'WMI_NATIVE_CONSENSUS')}
        }
        if ($native -and $native.Outcome -eq 'NOT_FOUND') {
            return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_SOURCE_MISMATCH'}
        }
        return [pscustomobject]@{Outcome='READY';Snapshot=(Copy-ManagedProcessSnapshot $wmi.Snapshot 'WMI_EXACT')}
    }

    if ($nativeExact) {
        if ($wmi -and $wmi.Outcome -eq 'NOT_FOUND') {
            return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_SOURCE_MISMATCH'}
        }
        if ($wmi -and $wmi.Outcome -eq 'READY' -and $wmi.Snapshot -and
                [int]$wmi.Snapshot.ProcessId -ne [int]$native.Snapshot.ProcessId) {
            return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_SOURCE_MISMATCH'}
        }
        return [pscustomobject]@{Outcome='READY';Snapshot=(Copy-ManagedProcessSnapshot $native.Snapshot 'NATIVE_EXACT')}
    }

    if ($wmi -and $wmi.Outcome -eq 'NOT_FOUND' -and $native -and $native.Outcome -eq 'NOT_FOUND') {
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
    }
    if ($native -and $native.Outcome -eq 'NOT_FOUND' -and $wmi -and
            $wmi.Outcome -in @('UNAVAILABLE','UNKNOWN')) {
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
    }
    if (($wmi -and $wmi.Outcome -eq 'READY') -xor ($native -and $native.Outcome -eq 'READY')) {
        return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_INCOMPLETE'}
    }
    return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'}
}

function Get-ManagedCoordinationOperations {
    param([Parameter(Mandatory)][string]$StateRoot)
    $directory = Join-Path $StateRoot 'operations'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return @() }
    $documents = [Collections.Generic.List[object]]::new()
    foreach ($file in @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File -ErrorAction Stop)) {
        try {
            $document = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
        } catch {
            throw "Coordination operation evidence is invalid: $($file.Name)"
        }
        $document | Add-Member -NotePropertyName EvidencePath -NotePropertyValue $file.FullName -Force
        $documents.Add($document)
    }
    return ,$documents.ToArray()
}

function Get-ManagedTextFingerprint {
    param([Parameter(Mandatory)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-ManagedNgrokCommandContract {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateRange(1,65535)][int]$ExpectedPort,
        [string]$ExpectedExecutablePath,
        [string[]]$ExpectedArguments,
        [Nullable[datetimeoffset]]$LaunchObservedAtUtc
    )
    if ([int]$Snapshot.ProcessId -le 0 -or [string]::IsNullOrWhiteSpace([string]$Snapshot.Name) -or
            [string]::IsNullOrWhiteSpace([string]$Snapshot.ExecutablePath) -or
            [string]::IsNullOrWhiteSpace([string]$Snapshot.CommandLine) -or -not $Snapshot.StartedAtUtc) {
        return [pscustomobject]@{Outcome='PENDING';Reason='The process snapshot is not complete.'}
    }
    $worktreePath = [IO.Path]::GetFullPath($Worktree).TrimEnd('\')
    if (-not (Test-Path -LiteralPath (Join-Path $worktreePath '.git'))) {
        return [pscustomobject]@{Outcome='REJECTED';Reason='The worktree identity is not registered.'}
    }
    $actualExecutable = [IO.Path]::GetFullPath([string]$Snapshot.ExecutablePath).TrimEnd('\')
    $actualName = [IO.Path]::GetFileName($actualExecutable)
    if (-not [string]::Equals([string]$Snapshot.Name, $actualName, [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals($actualName, 'ngrok.exe', [StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{Outcome='REJECTED';Reason='The process executable identity is not ngrok.exe.'}
    }
    if ($ExpectedExecutablePath) {
        $expectedExecutable = [IO.Path]::GetFullPath($ExpectedExecutablePath).TrimEnd('\')
        if (-not [string]::Equals($actualExecutable, $expectedExecutable, [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{Outcome='REJECTED';Reason='The process executable path does not match the launched executable.'}
        }
    }
    if ($null -ne $LaunchObservedAtUtc) {
        $processStartedAt = [datetimeoffset]$Snapshot.StartedAtUtc
        $observedLaunch = [datetimeoffset]$LaunchObservedAtUtc
        $delta = ($processStartedAt.ToUniversalTime() - $observedLaunch.ToUniversalTime()).TotalSeconds
        if ($delta -lt -2 -or $delta -gt 30) {
            return [pscustomobject]@{Outcome='REJECTED';Reason='The process start time does not match the observed launch window.'}
        }
    }

    $rawCommand = ([string]$Snapshot.CommandLine).Trim()
    $argumentText = $null
    $quotedExecutable = '"' + $actualExecutable + '"'
    $executablePrefixes = @($quotedExecutable, $actualExecutable, $actualName)
    foreach ($prefix in $executablePrefixes) {
        if ($rawCommand.Length -gt $prefix.Length -and
                $rawCommand.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and
                [char]::IsWhiteSpace($rawCommand[$prefix.Length])) {
            $argumentText = $rawCommand.Substring($prefix.Length).Trim()
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($argumentText)) {
        return [pscustomobject]@{Outcome='REJECTED';Reason='The process command executable does not match its inspected executable path.'}
    }
    $arguments = @($argumentText -split '\s+' | Where-Object { $_ })
    $target = "http://localhost:$ExpectedPort"
    $shapeMatches = $arguments.Count -eq 5 -and $arguments[0] -eq 'http' -and
        $arguments[1] -match '^--url=[^\s=]+$' -and $arguments[2] -eq $target -and
        $arguments[3] -eq '--log=stdout' -and $arguments[4] -eq '--log-level=warn'
    if (-not $shapeMatches) {
        return [pscustomobject]@{Outcome='REJECTED';Reason='The process command does not match the bounded ngrok lifecycle arguments.'}
    }
    if ($null -ne $ExpectedArguments) {
        $expectedArgumentText = (@($ExpectedArguments) -join ' ').Trim()
        if (-not [string]::Equals($argumentText, $expectedArgumentText, [StringComparison]::Ordinal)) {
            return [pscustomobject]@{Outcome='REJECTED';Reason='The process command does not exactly match the launched arguments.'}
        }
    }
    $executableFingerprint = Get-ManagedTextFingerprint $actualExecutable.ToLowerInvariant()
    $safeContract = "ngrok-v1|$executableFingerprint|$($worktreePath.ToLowerInvariant())|port=$ExpectedPort|http|url=redacted|target=localhost|log=stdout|level=warn"
    return [pscustomobject]@{
        Outcome='READY';Reason=$null;ExecutableFingerprint=$executableFingerprint
        CommandContractFingerprint=(Get-ManagedTextFingerprint $safeContract)
    }
}

function Test-ManagedProcessCommand {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [AllowNull()]$OwnershipReceipt,
        [switch]$ExactActiveCoordinationProof
    )
    $rawCommand = ([string]$Snapshot.CommandLine).ToLowerInvariant()
    $command = $rawCommand.Replace('/', '\')
    $worktreeToken = [IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant()
    $name = ([string]$Snapshot.Name).ToLowerInvariant()
    if ($Component -eq 'Ngrok') {
        if (-not $OwnershipReceipt -or -not $OwnershipReceipt.PSObject.Properties['executableFingerprint'] -or
                -not $OwnershipReceipt.PSObject.Properties['commandContractFingerprint'] -or
                -not $OwnershipReceipt.PSObject.Properties['appPort']) { return $false }
        $contract = Get-ManagedNgrokCommandContract -Snapshot $Snapshot -Worktree $Worktree `
            -ExpectedPort ([int]$OwnershipReceipt.appPort)
        return $contract.Outcome -eq 'READY' -and
            $contract.ExecutableFingerprint -eq [string]$OwnershipReceipt.executableFingerprint -and
            $contract.CommandContractFingerprint -eq [string]$OwnershipReceipt.commandContractFingerprint
    }
    if ([string]::IsNullOrWhiteSpace($rawCommand)) {
        return $ExactActiveCoordinationProof -and $name -in @('powershell.exe','pwsh.exe','cmd.exe','java.exe')
    }
    if (-not $command.Contains($worktreeToken) -or $command -notmatch 'coordinated-maven-run\.ps1') { return $false }
    if ($Component -eq 'Dispatcher') { return $command -match '(?i)-application\s+["'']?dispatcher(?:\s|$)' }
    return $command -match '(?i)-application\s+["'']?root(?:\s|$)'
}

function Test-ManagedEvidenceStartTime {
    param([Parameter(Mandatory)]$Snapshot, [Parameter(Mandatory)][string]$EvidenceStartedAt)
    $evidenceTime = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($EvidenceStartedAt, [ref]$evidenceTime)) { return $false }
    $processTime = [datetimeoffset]$Snapshot.StartedAtUtc
    $delta = ($evidenceTime.ToUniversalTime() - $processTime.ToUniversalTime()).TotalSeconds
    return $delta -ge -5 -and $delta -le 120
}

function ConvertTo-ManagedProcessStartReceipt {
    param(
        [Parameter(Mandatory)]$Receipt,
        [switch]$AllowNonManaged
    )
    if ($Receipt -isnot [pscustomobject]) {
        throw 'Managed process coordination receipt has an invalid document shape.'
    }
    $actionProperty = $Receipt.PSObject.Properties['action']
    if (-not $actionProperty) {
        if ($AllowNonManaged) { return $null }
        throw 'Managed process ownership receipt has an invalid typed schema.'
    }
    if ($actionProperty.Value -isnot [string]) {
        throw 'Managed process coordination receipt has an invalid action type.'
    }
    if ([string]$actionProperty.Value -ne 'managed-process-start') {
        if ($AllowNonManaged) { return $null }
        throw 'Managed process ownership receipt has an invalid typed schema.'
    }

    $values = @{}
    foreach ($name in @('component','processStartedAt','worktree','resource')) {
        $property = $Receipt.PSObject.Properties[$name]
        if (-not $property -or $property.Value -isnot [string] -or
                [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            throw 'Managed process start receipt is missing required typed evidence.'
        }
        $values[$name] = [string]$property.Value
    }
    if ($values.component -notin @('SpringBoot','Dispatcher','Ngrok')) {
        throw 'Managed process start receipt has an invalid component type.'
    }

    $processIdProperty = $Receipt.PSObject.Properties['processId']
    $integerTypes = @([byte],[sbyte],[int16],[uint16],[int32],[uint32],[int64],[uint64])
    if (-not $processIdProperty -or $null -eq $processIdProperty.Value -or
            $processIdProperty.Value.GetType() -notin $integerTypes) {
        throw 'Managed process start receipt has an invalid process id type.'
    }
    $validatedProcessId = [long]$processIdProperty.Value
    if ($validatedProcessId -le 0 -or $validatedProcessId -gt [int]::MaxValue) {
        throw 'Managed process start receipt has an invalid process id.'
    }

    $validatedStart = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($values.processStartedAt, [ref]$validatedStart)) {
        throw 'Managed process start receipt has invalid start-time evidence.'
    }
    return [pscustomobject]@{
        Document=$Receipt;Action='managed-process-start';Component=$values.component
        ProcessId=[int]$validatedProcessId;ProcessStartedAt=$validatedStart
        Worktree=$values.worktree;Resource=$values.resource
    }
}

function Read-ManagedOwnershipReceipt {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)]$Definition,
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][int]$ProcessId
    )
    $property = $State.PSObject.Properties[$Definition.ReceiptField]
    if (-not $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { return $null }
    $receiptId = [string]$property.Value
    if ($receiptId -notmatch '^[a-f0-9-]{36}$') { throw 'Managed process ownership receipt id is invalid.' }
    $path = Join-Path (Join-Path $StateRoot 'receipts') "$receiptId.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Managed process ownership receipt is missing.' }
    try { $receipt = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'Managed process ownership receipt is invalid.' }
    $typedReceipt = ConvertTo-ManagedProcessStartReceipt -Receipt $receipt
    $normalized = [IO.Path]::GetFullPath($Worktree).TrimEnd('\')
    if ($typedReceipt.Component -ne $Definition.Component -or $typedReceipt.ProcessId -ne $ProcessId -or
            -not [string]::Equals($typedReceipt.Worktree, $normalized, [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals($typedReceipt.Resource, $Definition.Resource, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Managed process ownership receipt does not match the requested worktree and component.'
    }
    $receipt | Add-Member -NotePropertyName EvidencePath -NotePropertyValue $path -Force
    return $receipt
}

function Assert-NoManagedStartReceiptReplay {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$Component,
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][datetimeoffset]$ProcessStartedAt
    )
    $directory = Join-Path $StateRoot 'receipts'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return }
    foreach ($file in @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File -ErrorAction Stop)) {
        try { $receipt = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json }
        catch { throw 'Managed process receipt replay fencing found invalid coordination evidence.' }
        $typedReceipt = ConvertTo-ManagedProcessStartReceipt -Receipt $receipt -AllowNonManaged
        if ($typedReceipt -and $typedReceipt.Component -eq $Component -and
                $typedReceipt.ProcessId -eq $ProcessId) {
            if ([Math]::Abs(($typedReceipt.ProcessStartedAt.ToUniversalTime() - $ProcessStartedAt.ToUniversalTime()).TotalMilliseconds) -lt 1) {
                throw 'Managed process ownership for this exact process generation was already published.'
            }
        }
    }
}

function New-ManagedProcessOwnershipReceipt {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][int]$ProcessId,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [string]$ExpectedExecutablePath,
        [string[]]$ExpectedArguments,
        [ValidateRange(1,65535)][int]$ExpectedPort = 8080,
        [Nullable[datetimeoffset]]$LaunchObservedAtUtc,
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id -TimeoutMilliseconds 500 },
        [scriptblock]$DelayAdapter = { param($milliseconds) Start-Sleep -Milliseconds $milliseconds },
        [ValidateRange(50,5000)][int]$SnapshotTimeoutMilliseconds = 1500,
        [switch]$PassThru
    )
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    if ($Component -eq 'Ngrok' -and ([string]::IsNullOrWhiteSpace($ExpectedExecutablePath) -or
            $null -eq $ExpectedArguments -or $ExpectedArguments.Count -eq 0 -or
            $null -eq $LaunchObservedAtUtc)) {
        throw 'Ngrok ownership publication requires the exact executable, arguments and observed launch time.'
    }
    $query = $null
    $launchContract = $null
    $pollMilliseconds = 100
    $maximumAttempts = [Math]::Max(1, [Math]::Ceiling($SnapshotTimeoutMilliseconds / [double]$pollMilliseconds) + 1)
    for ($attempt = 1; $attempt -le $maximumAttempts; $attempt++) {
        $query = & $ProcessQuery $ProcessId
        if ($Component -ne 'Ngrok') {
            if ($query -and $query.Outcome -eq 'READY') { break }
        } elseif ($query -and $query.Outcome -eq 'READY') {
            if ([int]$query.Snapshot.ProcessId -ne $ProcessId) {
                throw 'Process inspection returned a different PID than the launched process.'
            }
            $launchContract = Get-ManagedNgrokCommandContract -Snapshot $query.Snapshot -Worktree $Worktree `
                -ExpectedPort $ExpectedPort -ExpectedExecutablePath $ExpectedExecutablePath `
                -ExpectedArguments $ExpectedArguments -LaunchObservedAtUtc $LaunchObservedAtUtc
            if ($launchContract.Outcome -eq 'READY') { break }
            if ($launchContract.Outcome -eq 'REJECTED') {
                throw 'The launched ngrok process did not match the bounded repository lifecycle contract.'
            }
        }
        if ($attempt -lt $maximumAttempts) { & $DelayAdapter $pollMilliseconds }
    }
    if (-not $query -or $query.Outcome -ne 'READY' -or
            ($Component -eq 'Ngrok' -and (-not $launchContract -or $launchContract.Outcome -ne 'READY'))) {
        throw 'Managed process identity snapshot was not ready within the bounded publication window.'
    }
    Assert-NoManagedStartReceiptReplay -StateRoot $StateRoot -Component $Component -ProcessId $ProcessId `
        -ProcessStartedAt ([datetimeoffset]$query.Snapshot.StartedAtUtc)
    $receiptId = [guid]::NewGuid().ToString()
    $receiptDocument = [ordered]@{
        operationId=$receiptId;outcome='READY';action='managed-process-start';component=$Component
        processId=$ProcessId;processStartedAt=([datetimeoffset]$query.Snapshot.StartedAtUtc).ToString('o')
        worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\');resource=$definition.Resource
        disposition='managed-runtime; evidence-retained'
    }
    if ($Component -eq 'Ngrok') {
        $receiptDocument['appPort'] = $ExpectedPort
        $receiptDocument['executableFingerprint'] = $launchContract.ExecutableFingerprint
        $receiptDocument['commandContractFingerprint'] = $launchContract.CommandContractFingerprint
    }
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt $receiptDocument
    if ($PassThru) {
        return [pscustomobject]@{ReceiptId=$receiptId;ProcessId=$ProcessId;Snapshot=$query.Snapshot}
    }
    return $receiptId
}

function Get-ManagedProcessOwnershipProof {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$State,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [AllowNull()][object[]]$OperationDocuments
    )
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $trackedProperty = $State.PSObject.Properties[$definition.StateField]
    if (-not $trackedProperty -or -not $trackedProperty.Value) {
        return [pscustomobject]@{Outcome='READY';Disposition='ALREADY_STOPPED';Definition=$definition;ProcessId=$null;ProcessPresent=$false}
    }
    $processId = [int]$trackedProperty.Value
    if ($processId -le 0) { return [pscustomobject]@{Outcome='BLOCKED';Reason='Tracked PID is invalid.';Definition=$definition} }

    try { $receipt = Read-ManagedOwnershipReceipt -State $State -Definition $definition -StateRoot $StateRoot -Worktree $Worktree -ProcessId $processId }
    catch { return [pscustomobject]@{Outcome='BLOCKED';Reason=$_.Exception.Message;Definition=$definition;ProcessId=$processId} }
    try { $operations = if ($null -ne $OperationDocuments) { @($OperationDocuments) } else { @(Get-ManagedCoordinationOperations -StateRoot $StateRoot) } }
    catch { return [pscustomobject]@{Outcome='BLOCKED';Reason=$_.Exception.Message;Definition=$definition;ProcessId=$processId} }

    $matchingOperations = @($operations | Where-Object {
        [int]$_.ownerPid -eq $processId -and @($_.resources) -contains $definition.Resource -and $_.status -in @('ACTIVE','RELEASED')
    })
    $query = & $ProcessQuery $processId
    if (-not $query -or $query.Outcome -eq 'UNKNOWN') {
        $queryReason = if ($query -and $query.PSObject.Properties['Reason']) { [string]$query.Reason } else { 'no process query result' }
        return [pscustomobject]@{Outcome='BLOCKED';Reason="The tracked process could not be inspected: $queryReason";Definition=$definition;ProcessId=$processId}
    }

    $hasEvidence = $matchingOperations.Count -eq 1 -or $null -ne $receipt
    if ($query.Outcome -eq 'NOT_FOUND') {
        if (-not $hasEvidence) {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='The stale PID has no matching lifecycle or coordination evidence.';Definition=$definition;ProcessId=$processId}
        }
        $operation = if ($matchingOperations.Count -eq 1) { $matchingOperations[0] } else { $null }
        return [pscustomobject]@{Outcome='READY';Disposition='PROVEN_NOT_RUNNING';Definition=$definition;ProcessId=$processId;ProcessPresent=$false;Operation=$operation;Receipt=$receipt}
    }

    $snapshot = $query.Snapshot
    if ([int]$snapshot.ProcessId -ne $processId) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Process inspection returned a different PID.';Definition=$definition;ProcessId=$processId}
    }
    if ($matchingOperations.Count -gt 1) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Multiple coordination operations claim the same managed PID.';Definition=$definition;ProcessId=$processId}
    }
    $operation = if ($matchingOperations.Count -eq 1) { $matchingOperations[0] } else { $null }
    $trustedReceipt = $null -ne $receipt
    if (-not $operation -and -not $trustedReceipt) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='No exact worktree coordination resource proves ownership.';Definition=$definition;ProcessId=$processId}
    }
    $startedAt = if ($operation) { [string]$operation.startedAt } else { [string]$receipt.processStartedAt }
    if (-not (Test-ManagedEvidenceStartTime -Snapshot $snapshot -EvidenceStartedAt $startedAt)) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='PID creation time does not match ownership evidence; PID reuse is possible.';Definition=$definition;ProcessId=$processId}
    }
    $exactActiveOperation = $operation -and $operation.status -eq 'ACTIVE'
    if (-not (Test-ManagedProcessCommand -Snapshot $snapshot -Worktree $Worktree -Component $Component `
            -OwnershipReceipt $receipt -ExactActiveCoordinationProof:$exactActiveOperation)) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Process command does not match the managed component and exact worktree.';Definition=$definition;ProcessId=$processId}
    }

    foreach ($candidate in @($operations | Where-Object {
        $_.status -eq 'ACTIVE' -and @($_.resources) -contains $definition.Resource -and [int]$_.ownerPid -ne $processId
    })) {
        $other = & $ProcessQuery ([int]$candidate.ownerPid)
        if (-not $other -or $other.Outcome -eq 'UNKNOWN') {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='Another active owner on the exact resource cannot be classified.';Definition=$definition;ProcessId=$processId}
        }
        if ($other.Outcome -eq 'READY' -and (Test-ManagedEvidenceStartTime -Snapshot $other.Snapshot -EvidenceStartedAt ([string]$candidate.startedAt))) {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='Another active session legitimately owns the exact worktree resource.';Definition=$definition;ProcessId=$processId}
        }
    }
    return [pscustomobject]@{Outcome='READY';Disposition='OWNED_RUNNING';Definition=$definition;ProcessId=$processId;ProcessPresent=$true;Snapshot=$snapshot;Operation=$operation;Receipt=$receipt}
}

function Update-ManagedDevStateAtomic {
    param(
        [Parameter(Mandatory)][string]$StateFile,
        [Parameter(Mandatory)]$Definition
    )
    $guard = New-CoordinationMutex "service-state/$StateFile"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $guard -Deadline ([datetime]::UtcNow.AddSeconds(10))
        if (-not $held) { throw 'Development state is BUSY; component ownership was not changed.' }
        $state = [IO.File]::ReadAllText($StateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $merged = @{}
        foreach ($property in $state.PSObject.Properties) { $merged[$property.Name] = $property.Value }
        $merged[$Definition.StateField] = $null
        $merged[$Definition.ReceiptField] = $null
        if ($Definition.Component -eq 'Ngrok') { $merged['ngrokUrl'] = $null }
        if ($Definition.Component -eq 'Dispatcher') { $merged['dispatcherArmed'] = $false }
        Write-CoordinationJsonAtomic -Path $StateFile -Document $merged
    } finally {
        if ($held) { $guard.ReleaseMutex() }
        $guard.Dispose()
    }
}

function Complete-ManagedComponentStop {
    param(
        [Parameter(Mandatory)]$Proof,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][string]$StateFile,
        [string]$StateRoot = (Get-CoordinationDefaultRoot)
    )
    if ($Proof.Operation -and $Proof.Operation.EvidencePath -and
            (Test-Path -LiteralPath $Proof.Operation.EvidencePath -PathType Leaf)) {
        $manifest = [IO.File]::ReadAllText($Proof.Operation.EvidencePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ([int]$manifest.ownerPid -ne [int]$Proof.ProcessId -or
                -not (@($manifest.resources) -contains $Proof.Definition.Resource)) {
            throw 'Coordination ownership changed before stop completion; state was not cleared.'
        }
        if ($manifest.status -eq 'ACTIVE') {
            $manifest.status = 'RELEASED'
            $manifest | Add-Member -NotePropertyName releasedAt -NotePropertyValue ([datetime]::UtcNow.ToString('o')) -Force
            $manifest | Add-Member -NotePropertyName releaseReason -NotePropertyValue 'verified-managed-process-stop' -Force
            Write-CoordinationJsonAtomic -Path $Proof.Operation.EvidencePath -Document $manifest
        }
    }
    Update-ManagedDevStateAtomic -StateFile $StateFile -Definition $Proof.Definition
    $receiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
        operationId=$receiptId;outcome='READY';action='managed-process-stop';component=$Proof.Definition.Component
        processId=$Proof.ProcessId;worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\')
        resource=$Proof.Definition.Resource;disposition='evidence-retained; component-state-cleared'
    })
    return $receiptId
}

function Invoke-ManagedComponentStop {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [AllowNull()][object[]]$OperationDocuments,
        [Parameter(Mandatory)][scriptblock]$StopAdapter
    )
    $stateFile = Join-Path $Worktree 'scripts\.dev-state.json'
    $state = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $proof = Get-ManagedProcessOwnershipProof -Worktree $Worktree -Component $Component -State $state `
        -StateRoot $StateRoot -ProcessQuery $ProcessQuery -OperationDocuments $OperationDocuments
    if ($proof.Outcome -ne 'READY') { return $proof }
    if ($proof.Disposition -eq 'ALREADY_STOPPED') {
        return [pscustomobject]@{Outcome='READY';Disposition='ALREADY_STOPPED';Component=$Component;Changed=$false}
    }
    if ($proof.ProcessPresent) {
        $stopResult = & $StopAdapter $proof
        if (-not $stopResult -or -not $stopResult.Success) {
            return [pscustomobject]@{Outcome='RECOVERY_REQUIRED';Reason="$Component stop verification failed; ownership was retained.";Proof=$proof}
        }
    }
    $receiptId = Complete-ManagedComponentStop -Proof $proof -Worktree $Worktree -StateFile $stateFile -StateRoot $StateRoot
    return [pscustomobject]@{Outcome='READY';Disposition=$proof.Disposition;Component=$Component;Changed=$true;ReceiptId=$receiptId;Proof=$proof}
}


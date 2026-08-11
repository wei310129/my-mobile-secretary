Set-StrictMode -Version Latest

if (-not (Get-Command Write-CoordinationJsonAtomic -ErrorAction SilentlyContinue)) {
    . "$PSScriptRoot\coordination-common.ps1"
}

function Initialize-WindowsDurableManagedProcessType {
    if ('Mms.Tooling.DurableManagedProcess' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace Mms.Tooling {
    public static class DurableManagedProcess {
        private const uint GENERIC_WRITE = 0x40000000;
        private const uint FILE_SHARE_READ = 0x1;
        private const uint FILE_SHARE_WRITE = 0x2;
        private const uint CREATE_ALWAYS = 2;
        private const uint FILE_ATTRIBUTE_NORMAL = 0x80;
        private const uint STARTF_USESTDHANDLES = 0x100;
        private const uint CREATE_BREAKAWAY_FROM_JOB = 0x01000000;
        private const uint CREATE_NO_WINDOW = 0x08000000;
        private const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
        private static readonly IntPtr PROC_THREAD_ATTRIBUTE_HANDLE_LIST = new IntPtr(0x00020002);
        private static readonly IntPtr INVALID_HANDLE_VALUE = new IntPtr(-1);

        [StructLayout(LayoutKind.Sequential)]
        private struct SECURITY_ATTRIBUTES {
            public int Length;
            public IntPtr SecurityDescriptor;
            [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct STARTUPINFO {
            public int cb;
            public string reserved;
            public string desktop;
            public string title;
            public uint x;
            public uint y;
            public uint xSize;
            public uint ySize;
            public uint xCountChars;
            public uint yCountChars;
            public uint fillAttribute;
            public uint flags;
            public ushort showWindow;
            public ushort reserved2Length;
            public IntPtr reserved2;
            public IntPtr standardInput;
            public IntPtr standardOutput;
            public IntPtr standardError;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct PROCESS_INFORMATION {
            public IntPtr process;
            public IntPtr thread;
            public int processId;
            public int threadId;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct STARTUPINFOEX {
            public STARTUPINFO startupInfo;
            public IntPtr attributeList;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFile(string fileName, uint desiredAccess, uint shareMode,
            ref SECURITY_ATTRIBUTES securityAttributes, uint creationDisposition, uint flags, IntPtr template);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CreateProcess(string applicationName, StringBuilder commandLine,
            IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags,
            IntPtr environment, string currentDirectory, ref STARTUPINFOEX startupInfo,
            out PROCESS_INFORMATION processInformation);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool InitializeProcThreadAttributeList(IntPtr attributeList, int attributeCount,
            int flags, ref IntPtr size);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool UpdateProcThreadAttribute(IntPtr attributeList, uint flags, IntPtr attribute,
            IntPtr value, IntPtr size, IntPtr previousValue, IntPtr returnSize);

        [DllImport("kernel32.dll")]
        private static extern void DeleteProcThreadAttributeList(IntPtr attributeList);

        [DllImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CloseHandle(IntPtr handle);

        public static int Launch(string executable, string commandLine, string workingDirectory,
            string standardOutputPath, string standardErrorPath) {
            SECURITY_ATTRIBUTES security = new SECURITY_ATTRIBUTES();
            security.Length = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES));
            security.InheritHandle = true;
            IntPtr output = CreateFile(standardOutputPath, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                ref security, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
            if (output == INVALID_HANDLE_VALUE) { throw new Win32Exception(Marshal.GetLastWin32Error(), "Managed output log could not be opened."); }
            IntPtr error = CreateFile(standardErrorPath, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                ref security, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
            if (error == INVALID_HANDLE_VALUE) {
                CloseHandle(output);
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Managed error log could not be opened.");
            }
            PROCESS_INFORMATION information = new PROCESS_INFORMATION();
            IntPtr attributeList = IntPtr.Zero;
            IntPtr handleList = IntPtr.Zero;
            try {
                IntPtr attributeSize = IntPtr.Zero;
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeSize);
                attributeList = Marshal.AllocHGlobal(attributeSize);
                if (!InitializeProcThreadAttributeList(attributeList, 1, 0, ref attributeSize)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Managed handle allowlist initialization failed.");
                }
                handleList = Marshal.AllocHGlobal(IntPtr.Size * 2);
                Marshal.WriteIntPtr(handleList, 0, output);
                Marshal.WriteIntPtr(handleList, IntPtr.Size, error);
                if (!UpdateProcThreadAttribute(attributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                        handleList, new IntPtr(IntPtr.Size * 2), IntPtr.Zero, IntPtr.Zero)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Managed handle allowlist update failed.");
                }
                STARTUPINFOEX startup = new STARTUPINFOEX();
                startup.startupInfo.cb = Marshal.SizeOf(typeof(STARTUPINFOEX));
                startup.startupInfo.flags = STARTF_USESTDHANDLES;
                startup.startupInfo.standardOutput = output;
                startup.startupInfo.standardError = error;
                startup.startupInfo.standardInput = IntPtr.Zero;
                startup.attributeList = attributeList;
                bool created = CreateProcess(executable, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero,
                    true, CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT, IntPtr.Zero, workingDirectory,
                    ref startup, out information);
                if (!created) { throw new Win32Exception(Marshal.GetLastWin32Error(), "Durable managed process launch failed."); }
                return information.processId;
            } finally {
                if (information.thread != IntPtr.Zero) { CloseHandle(information.thread); }
                if (information.process != IntPtr.Zero) { CloseHandle(information.process); }
                if (attributeList != IntPtr.Zero) { DeleteProcThreadAttributeList(attributeList);Marshal.FreeHGlobal(attributeList); }
                if (handleList != IntPtr.Zero) { Marshal.FreeHGlobal(handleList); }
                CloseHandle(output);
                CloseHandle(error);
            }
        }
    }
}
'@
}

function ConvertTo-WindowsManagedCommandLineToken {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $builder = [Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') { $slashes++; continue }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (($slashes * 2) + 1)))
            [void]$builder.Append('"')
        } else {
            if ($slashes -gt 0) { [void]$builder.Append(('\' * $slashes)) }
            [void]$builder.Append($character)
        }
        $slashes = 0
    }
    if ($slashes -gt 0) { [void]$builder.Append(('\' * ($slashes * 2))) }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-WindowsDurableManagedProcessLaunch {
    param([Parameter(Mandatory)]$Request)
    if ($env:OS -ne 'Windows_NT') { throw 'Durable managed process launch requires Windows.' }
    Initialize-WindowsDurableManagedProcessType
    $tokens = @((ConvertTo-WindowsManagedCommandLineToken $Request.ExecutablePath)) +
        @($Request.Arguments | ForEach-Object { ConvertTo-WindowsManagedCommandLineToken ([string]$_) })
    $processId = [Mms.Tooling.DurableManagedProcess]::Launch(
        $Request.ExecutablePath, ($tokens -join ' '), $Request.WorkingDirectory,
        $Request.StandardOutputPath, $Request.StandardErrorPath)
    return [pscustomobject]@{ProcessId=$processId;DurabilityClass='WINDOWS_JOB_BREAKAWAY'}
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
        $reasonCode = if ($_.Exception -is [UnauthorizedAccessException] -or
                ($_.Exception.PSObject.Properties['ErrorCode'] -and [int]$_.Exception.ErrorCode -eq 5)) {
            'WMI_QUERY_ACCESS_DENIED'
        } else { 'WMI_QUERY_UNAVAILABLE' }
        return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode=$reasonCode}
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
        $reasonCode = if (($_.Exception -is [ComponentModel.Win32Exception] -and
                [int]$_.Exception.NativeErrorCode -eq 5) -or $_.Exception -is [UnauthorizedAccessException]) {
            'NATIVE_QUERY_ACCESS_DENIED'
        } else { 'NATIVE_EXACT_QUERY_UNAVAILABLE' }
        $process = $null
        try {
            $process = [Diagnostics.Process]::GetProcessById($ProcessId)
            return [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode=$reasonCode}
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

function ConvertTo-ManagedProcessCanonicalStartTime {
    param([Parameter(Mandatory)][datetimeoffset]$Value)
    $utc = $Value.ToUniversalTime()
    $ticksPerMicrosecond = 10L
    return $utc.AddTicks(-($utc.Ticks % $ticksPerMicrosecond))
}

function Format-ManagedProcessCanonicalStartTime {
    param([Parameter(Mandatory)][datetimeoffset]$Value)
    return (ConvertTo-ManagedProcessCanonicalStartTime -Value $Value).ToString(
        "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", [Globalization.CultureInfo]::InvariantCulture)
}

function Test-ManagedProcessCanonicalStartTimeEqual {
    param(
        [Parameter(Mandatory)][datetimeoffset]$Left,
        [Parameter(Mandatory)][datetimeoffset]$Right
    )
    return (ConvertTo-ManagedProcessCanonicalStartTime -Value $Left).Ticks -eq
        (ConvertTo-ManagedProcessCanonicalStartTime -Value $Right).Ticks
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
    return Test-ManagedProcessCanonicalStartTimeEqual -Left ([datetimeoffset]$Left.StartedAtUtc) `
        -Right ([datetimeoffset]$Right.StartedAtUtc)
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
    $reasonCodes = @(
        if ($wmi -and $wmi.PSObject.Properties['ReasonCode']) { [string]$wmi.ReasonCode }
        if ($native -and $native.PSObject.Properties['ReasonCode']) { [string]$native.ReasonCode }
    )
    if (@($reasonCodes | Where-Object { $_ -match 'ACCESS_DENIED' }).Count -gt 0 -and
            @($reasonCodes | Where-Object { $_ -and $_ -notmatch 'ACCESS_DENIED|PLATFORM_UNSUPPORTED' }).Count -eq 0) {
        return [pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';Snapshot=$null;ReasonCode='PROCESS_QUERY_ACCESS_DENIED'}
    }
    return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'}
}

function Get-ManagedProcessObservation {
    param([AllowNull()]$QueryResult)
    if (-not $QueryResult) {
        return [pscustomobject]@{State='UNKNOWN';ReasonCode='PROCESS_QUERY_MISSING'}
    }
    switch ([string]$QueryResult.Outcome) {
        'READY' { return [pscustomobject]@{State='UP';ReasonCode=$null} }
        'NOT_FOUND' { return [pscustomobject]@{State='DOWN';ReasonCode='PROCESS_NOT_FOUND'} }
        'CALLER_ACCESS_DENIED' { return [pscustomobject]@{State='CALLER_ACCESS_DENIED';ReasonCode='PROCESS_QUERY_ACCESS_DENIED'} }
        'UNAVAILABLE' {
            if ([string]$QueryResult.ReasonCode -match 'ACCESS_DENIED') {
                return [pscustomobject]@{State='CALLER_ACCESS_DENIED';ReasonCode='PROCESS_QUERY_ACCESS_DENIED'}
            }
        }
    }
    return [pscustomobject]@{State='UNKNOWN';ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'}
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

function Get-ManagedProcessCommandFingerprint {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [AllowNull()]$OwnershipReceipt,
        [switch]$ExactActiveCoordinationProof
    )
    if (-not (Test-ManagedProcessSnapshotComplete -Snapshot $Snapshot)) {
        throw 'Managed process identity is incomplete.'
    }
    if (-not (Test-ManagedProcessCommand -Snapshot $Snapshot -Worktree $Worktree -Component $Component `
            -OwnershipReceipt $OwnershipReceipt -ExactActiveCoordinationProof:$ExactActiveCoordinationProof)) {
        throw 'Managed process command does not match the bounded component contract.'
    }
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $startedAt = Format-ManagedProcessCanonicalStartTime -Value ([datetimeoffset]$Snapshot.StartedAtUtc)
    $safeIdentity = @(
        'managed-process-command-v2', $Component, (Get-ManagedTextFingerprint $definition.Resource),
        [string][int]$Snapshot.ProcessId, $startedAt,
        (Get-ManagedTextFingerprint ([string]$Snapshot.ExecutablePath).ToLowerInvariant()),
        (Get-ManagedTextFingerprint ([string]$Snapshot.CommandLine))
    ) -join '|'
    return Get-ManagedTextFingerprint $safeIdentity
}

function Get-LegacyManagedProcessCommandFingerprint {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][datetimeoffset]$ReceiptStartedAt,
        [AllowNull()]$OwnershipReceipt,
        [switch]$ExactActiveCoordinationProof
    )
    if (-not (Test-ManagedProcessSnapshotComplete -Snapshot $Snapshot)) {
        throw 'Managed process identity is incomplete.'
    }
    if (-not (Test-ManagedProcessCommand -Snapshot $Snapshot -Worktree $Worktree -Component $Component `
            -OwnershipReceipt $OwnershipReceipt -ExactActiveCoordinationProof:$ExactActiveCoordinationProof)) {
        throw 'Managed process command does not match the bounded component contract.'
    }
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $safeIdentity = @(
        'managed-process-command-v1', $Component, (Get-ManagedTextFingerprint $definition.Resource),
        [string][int]$Snapshot.ProcessId, $ReceiptStartedAt.ToUniversalTime().ToString('o'),
        (Get-ManagedTextFingerprint ([string]$Snapshot.ExecutablePath).ToLowerInvariant()),
        (Get-ManagedTextFingerprint ([string]$Snapshot.CommandLine))
    ) -join '|'
    return Get-ManagedTextFingerprint $safeIdentity
}

function Test-ManagedProcessOwnershipIdentityFingerprint {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$OwnershipReceipt,
        [switch]$ExactActiveCoordinationProof
    )
    if (-not $OwnershipReceipt.PSObject.Properties['ownershipIdentityFingerprint']) { return $false }
    $receiptStartedAt = [datetimeoffset]::MinValue
    if (-not $OwnershipReceipt.PSObject.Properties['processStartedAt'] -or
            -not [datetimeoffset]::TryParse([string]$OwnershipReceipt.processStartedAt, [ref]$receiptStartedAt) -or
            -not (Test-ManagedProcessCanonicalStartTimeEqual -Left $receiptStartedAt `
                -Right ([datetimeoffset]$Snapshot.StartedAtUtc))) {
        return $false
    }
    $version = $OwnershipReceipt.PSObject.Properties['identityFingerprintVersion']
    $precision = $OwnershipReceipt.PSObject.Properties['startTimeCanonicalPrecision']
    if ($version -or $precision) {
        if (-not $version -or -not $precision -or
                [string]$version.Value -ne 'managed-process-command-v2' -or
                [string]$precision.Value -ne 'UTC_MICROSECOND_TRUNCATED') {
            return $false
        }
        $expected = Get-ManagedProcessCommandFingerprint -Snapshot $Snapshot -Worktree $Worktree `
            -Component $Component -OwnershipReceipt $OwnershipReceipt `
            -ExactActiveCoordinationProof:$ExactActiveCoordinationProof
    } else {
        $expected = Get-LegacyManagedProcessCommandFingerprint -Snapshot $Snapshot -Worktree $Worktree `
            -Component $Component -ReceiptStartedAt $receiptStartedAt -OwnershipReceipt $OwnershipReceipt `
            -ExactActiveCoordinationProof:$ExactActiveCoordinationProof
    }
    return [string]$OwnershipReceipt.ownershipIdentityFingerprint -eq $expected
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
            if (Test-ManagedProcessCanonicalStartTimeEqual -Left $typedReceipt.ProcessStartedAt -Right $ProcessStartedAt) {
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
        [string]$Generation,
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
        operationId=$receiptId;outcome='READY';status='READY';action='managed-process-start';component=$Component
        processId=$ProcessId;processStartedAt=(Format-ManagedProcessCanonicalStartTime -Value ([datetimeoffset]$query.Snapshot.StartedAtUtc))
        worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\');resource=$definition.Resource
        identityFingerprintVersion='managed-process-command-v2';startTimeCanonicalPrecision='UTC_MICROSECOND_TRUNCATED'
        disposition='managed-runtime; evidence-retained'
    }
    if ($Component -eq 'Ngrok') {
        $receiptDocument['appPort'] = $ExpectedPort
        $receiptDocument['executableFingerprint'] = $launchContract.ExecutableFingerprint
        $receiptDocument['commandContractFingerprint'] = $launchContract.CommandContractFingerprint
    } else {
        $receiptDocument['commandContractFingerprint'] = Get-ManagedProcessCommandFingerprint `
            -Snapshot $query.Snapshot -Worktree $Worktree -Component $Component -ExactActiveCoordinationProof
    }
    $receiptDocument['ownershipIdentityFingerprint'] = Get-ManagedProcessCommandFingerprint `
        -Snapshot $query.Snapshot -Worktree $Worktree -Component $Component `
        -OwnershipReceipt ([pscustomobject]$receiptDocument) -ExactActiveCoordinationProof
    if (-not [string]::IsNullOrWhiteSpace($Generation)) { $receiptDocument['generation'] = $Generation }
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt $receiptDocument
    if ($PassThru) {
        return [pscustomobject]@{ReceiptId=$receiptId;ProcessId=$ProcessId;Snapshot=$query.Snapshot}
    }
    return $receiptId
}

function Set-ManagedProcessOwnershipReceiptStatus {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$ReceiptId,
        [Parameter(Mandatory)][ValidateSet('READY','REVOKED')][string]$Status,
        [string]$ReasonCode
    )
    if ($ReceiptId -notmatch '^[a-f0-9-]{36}$') { throw 'Managed process ownership receipt id is invalid.' }
    $path = Join-Path (Join-Path $StateRoot 'receipts') "$ReceiptId.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Managed process ownership receipt is missing.' }
    $receipt = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $typed = ConvertTo-ManagedProcessStartReceipt -Receipt $receipt
    if ($typed.Document.PSObject.Properties['status'] -and [string]$typed.Document.status -eq 'REVOKED') { return }
    $receipt | Add-Member -NotePropertyName status -NotePropertyValue $Status -Force
    if ($Status -eq 'REVOKED') {
        $receipt | Add-Member -NotePropertyName revokedAt -NotePropertyValue ([datetimeoffset]::UtcNow.ToString('o')) -Force
        $receipt | Add-Member -NotePropertyName revocationReasonCode -NotePropertyValue $ReasonCode -Force
    }
    Write-CoordinationJsonAtomic -Path $path -Document $receipt
}

function Confirm-ManagedRuntimePublication {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,128}$')][string]$Generation,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [string]$ExpectedExecutablePath,
        [string[]]$ExpectedArguments,
        [ValidateRange(1,65535)][int]$ExpectedPort = 8080,
        [Nullable[datetimeoffset]]$LaunchObservedAtUtc,
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id -TimeoutMilliseconds 500 },
        [Parameter(Mandatory)][scriptblock]$ReadinessProbe,
        [scriptblock]$PublicationBoundary = { },
        [scriptblock]$DelayAdapter = { param($milliseconds) Start-Sleep -Milliseconds $milliseconds },
        [ValidateRange(50,5000)][int]$SnapshotTimeoutMilliseconds = 1500
    )
    $before = & $ProcessQuery $ProcessId
    $beforeObservation = Get-ManagedProcessObservation -QueryResult $before
    if ($beforeObservation.State -ne 'UP') {
        throw 'Managed process identity was not ready before ownership publication.'
    }
    $readiness = & $ReadinessProbe 'BEFORE_PUBLICATION' $ProcessId
    if (-not $readiness -or -not [bool]$readiness.Ready) {
        throw 'Managed runtime readiness was not proven before ownership publication.'
    }

    $arguments = @{
        Worktree=$Worktree;Component=$Component;ProcessId=$ProcessId;StateRoot=$StateRoot
        ProcessQuery=({ param($id) return $before }).GetNewClosure();DelayAdapter=$DelayAdapter
        SnapshotTimeoutMilliseconds=$SnapshotTimeoutMilliseconds;Generation=$Generation;PassThru=$true
    }
    if ($Component -eq 'Ngrok') {
        $arguments.ExpectedExecutablePath=$ExpectedExecutablePath;$arguments.ExpectedArguments=$ExpectedArguments
        $arguments.ExpectedPort=$ExpectedPort;$arguments.LaunchObservedAtUtc=$LaunchObservedAtUtc
    }
    $published = New-ManagedProcessOwnershipReceipt @arguments
    try {
        & $PublicationBoundary
        $after = & $ProcessQuery $ProcessId
        $afterObservation = Get-ManagedProcessObservation -QueryResult $after
        if ($afterObservation.State -ne 'UP' -or
                -not (Test-ManagedProcessSnapshotConsensus -Left $before.Snapshot -Right $after.Snapshot)) {
            throw 'Managed process did not survive the ownership publication boundary.'
        }
        $postReadiness = & $ReadinessProbe 'AFTER_PUBLICATION' $ProcessId
        if (-not $postReadiness -or -not [bool]$postReadiness.Ready) {
            throw 'Managed runtime readiness did not survive the ownership publication boundary.'
        }
        return $published
    } catch {
        Set-ManagedProcessOwnershipReceiptStatus -StateRoot $StateRoot -ReceiptId $published.ReceiptId `
            -Status REVOKED -ReasonCode 'POST_PUBLICATION_RECHECK_FAILED'
        throw
    }
}

function Start-ManagedDurableProcess {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][string]$ExecutablePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,128}$')][string]$Generation,
        [Parameter(Mandatory)][string]$StandardOutputPath,
        [Parameter(Mandatory)][string]$StandardErrorPath,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [ValidateRange(1,65535)][int]$ExpectedPort = 8080,
        [Parameter(Mandatory)][scriptblock]$ReadinessProbe,
        [scriptblock]$LaunchAdapter = { param($request) Invoke-WindowsDurableManagedProcessLaunch -Request $request },
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id -TimeoutMilliseconds 500 },
        [scriptblock]$PublicationBoundary = { param($id) Start-Sleep -Milliseconds 500 },
        [scriptblock]$FailureStopAdapter
    )
    $normalizedWorktree = [IO.Path]::GetFullPath($Worktree).TrimEnd('\')
    if (-not (Test-Path -LiteralPath (Join-Path $normalizedWorktree '.git'))) {
        throw 'Durable managed process launch requires a registered worktree.'
    }
    $logsRoot = [IO.Path]::GetFullPath((Join-Path $normalizedWorktree 'scripts\.logs')).TrimEnd('\') + '\'
    $outputPath = [IO.Path]::GetFullPath($StandardOutputPath)
    $errorPath = [IO.Path]::GetFullPath($StandardErrorPath)
    if (-not $outputPath.StartsWith($logsRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not $errorPath.StartsWith($logsRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Durable managed process logs must remain inside the owned scripts log directory.'
    }
    [IO.Directory]::CreateDirectory($logsRoot) | Out-Null
    $launchObservedAt = [datetimeoffset]::UtcNow
    $request = [pscustomobject]@{
        Component=$Component;ExecutablePath=[IO.Path]::GetFullPath($ExecutablePath);Arguments=@($Arguments)
        WorkingDirectory=$normalizedWorktree;StandardOutputPath=$outputPath;StandardErrorPath=$errorPath
        Generation=$Generation
    }
    $launch = & $LaunchAdapter $request
    if (-not $launch -or [int]$launch.ProcessId -le 0 -or [string]$launch.DurabilityClass -ne 'WINDOWS_JOB_BREAKAWAY') {
        throw 'Managed child launch did not prove the durable breakaway contract.'
    }
    $confirmArguments = @{
        Worktree=$normalizedWorktree;Component=$Component;ProcessId=[int]$launch.ProcessId;Generation=$Generation
        StateRoot=$StateRoot;ProcessQuery=$ProcessQuery;ReadinessProbe=$ReadinessProbe
        PublicationBoundary=({ & $PublicationBoundary ([int]$launch.ProcessId) }).GetNewClosure()
        ExpectedPort=$ExpectedPort;LaunchObservedAtUtc=$launchObservedAt
    }
    if ($Component -eq 'Ngrok') {
        $confirmArguments.ExpectedExecutablePath=$request.ExecutablePath
        $confirmArguments.ExpectedArguments=@($Arguments)
    }
    try {
        return Confirm-ManagedRuntimePublication @confirmArguments
    } catch {
        if ($FailureStopAdapter) {
            try { & $FailureStopAdapter ([int]$launch.ProcessId) | Out-Null } catch { }
        }
        throw
    }
}

function Assert-ManagedRuntimeReceiptCurrent {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$State,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id -TimeoutMilliseconds 500 },
        [Parameter(Mandatory)][scriptblock]$ReadinessProbe
    )
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $pidProperty = $State.PSObject.Properties[$definition.StateField]
    if (-not $pidProperty -or [int]$pidProperty.Value -le 0) { throw 'Managed runtime state has no exact process identity.' }
    $processId = [int]$pidProperty.Value
    $receipt = Read-ManagedOwnershipReceipt -State $State -Definition $definition -StateRoot $StateRoot `
        -Worktree $Worktree -ProcessId $processId
    if (-not $receipt -or ($receipt.PSObject.Properties['status'] -and [string]$receipt.status -ne 'READY') -or
            -not $receipt.PSObject.Properties['generation'] -or -not $State.PSObject.Properties['serviceGeneration'] -or
            [string]$receipt.generation -ne [string]$State.serviceGeneration) {
        throw 'Managed runtime ownership receipt does not match the durable service generation.'
    }
    $query = & $ProcessQuery $processId
    $observation = Get-ManagedProcessObservation -QueryResult $query
    if ($observation.State -ne 'UP') { throw 'Managed runtime process is not observable after durable state publication.' }
    $expectedStart = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse([string]$receipt.processStartedAt, [ref]$expectedStart) -or
            -not (Test-ManagedProcessCanonicalStartTimeEqual -Left $expectedStart `
                -Right ([datetimeoffset]$query.Snapshot.StartedAtUtc))) {
        throw 'Managed runtime process generation changed after durable state publication.'
    }
    if (-not (Test-ManagedProcessOwnershipIdentityFingerprint -Snapshot $query.Snapshot -Worktree $Worktree `
            -Component $Component -OwnershipReceipt $receipt -ExactActiveCoordinationProof)) {
        throw 'Managed runtime command identity changed after durable state publication.'
    }
    $readiness = & $ReadinessProbe
    if (-not $readiness -or -not [bool]$readiness.Ready) {
        throw 'Managed runtime readiness failed after durable state publication.'
    }
    return [pscustomobject]@{Outcome='READY';Component=$Component;ProcessId=$processId;ReceiptId=[string]$receipt.operationId}
}

function Get-ManagedLegacyStateOwnershipEvidence {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher')][string]$Component,
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)]$Snapshot
    )
    $generation = if ($State.PSObject.Properties['serviceGeneration']) { [string]$State.serviceGeneration } else { $null }
    $identity = if ($State.PSObject.Properties['runtimeContentIdentity']) { $State.runtimeContentIdentity } else { $null }
    $stateStartedAt = [datetimeoffset]::MinValue
    if ($generation -notmatch '^[A-Za-z0-9._-]{1,128}$' -or -not $identity -or
            -not $identity.PSObject.Properties['serviceGeneration'] -or [string]$identity.serviceGeneration -ne $generation -or
            -not $State.PSObject.Properties['startedAt'] -or
            -not [datetimeoffset]::TryParse([string]$State.startedAt, [ref]$stateStartedAt)) {
        return [pscustomobject]@{Ready=$false;ReasonCode='LEGACY_GENERATION_EVIDENCE_INVALID'}
    }
    $processStartedAt = ConvertTo-ManagedProcessCanonicalStartTime -Value ([datetimeoffset]$Snapshot.StartedAtUtc)
    $publicationDelay = ($stateStartedAt.ToUniversalTime() - $processStartedAt.ToUniversalTime()).TotalSeconds
    if ($publicationDelay -lt 0 -or $publicationDelay -gt 300) {
        return [pscustomobject]@{Ready=$false;ReasonCode='LEGACY_PROCESS_START_WINDOW_MISMATCH'}
    }
    if (-not $State.PSObject.Properties['serviceLogDirectory'] -or
            [string]::IsNullOrWhiteSpace([string]$State.serviceLogDirectory)) {
        return [pscustomobject]@{Ready=$false;ReasonCode='LEGACY_GENERATION_LOG_IDENTITY_MISSING'}
    }
    try {
        $logsRoot = [IO.Path]::GetFullPath((Join-Path $Worktree 'scripts\.logs\generations')).TrimEnd('\') + '\'
        $logDirectory = [IO.Path]::GetFullPath([string]$State.serviceLogDirectory).TrimEnd('\')
        if (-not $logDirectory.StartsWith($logsRoot,[StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($logDirectory) -ne $generation) {
            return [pscustomobject]@{Ready=$false;ReasonCode='LEGACY_GENERATION_LOG_IDENTITY_MISMATCH'}
        }
        $fingerprint = Get-ManagedProcessCommandFingerprint -Snapshot $Snapshot -Worktree $Worktree `
            -Component $Component -ExactActiveCoordinationProof
    } catch {
        return [pscustomobject]@{Ready=$false;ReasonCode='LEGACY_COMMAND_CONTRACT_MISMATCH'}
    }
    return [pscustomobject]@{
        Ready=$true;ReasonCode=$null;Generation=$generation;ProcessStartedAt=$processStartedAt
        CommandContractFingerprint=$fingerprint;EvidenceKind='LEGACY_DURABLE_STATE'
    }
}

function Get-ManagedOrphanDiagnosis {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$State,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [AllowNull()][object[]]$OperationDocuments
    )
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $tracked = $State.PSObject.Properties[$definition.StateField]
    if (-not $tracked -or -not $tracked.Value) {
        return [pscustomobject]@{Classification='DOWN';ReasonCode='STATE_HAS_NO_TRACKED_PROCESS';Component=$Component}
    }
    $processId = [int]$tracked.Value
    if ($processId -le 0) {
        return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='TRACKED_PID_INVALID';Component=$Component}
    }
    $query = & $ProcessQuery $processId
    $observation = Get-ManagedProcessObservation -QueryResult $query
    if ($observation.State -ne 'UP') {
        $classification = if ($observation.State -eq 'DOWN') { 'ORPHAN_PROVEN_DOWN' } else { 'ORPHAN_UNVERIFIABLE' }
        return [pscustomobject]@{Classification=$classification;ReasonCode=$observation.ReasonCode;Component=$Component;ProcessId=$processId}
    }
    try {
        $receipt = Read-ManagedOwnershipReceipt -State $State -Definition $definition -StateRoot $StateRoot `
            -Worktree $Worktree -ProcessId $processId
    } catch {
        return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='OWNERSHIP_RECEIPT_INVALID';Component=$Component;ProcessId=$processId}
    }
    if (-not $receipt) {
        if ($Component -eq 'Ngrok') {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='OWNERSHIP_RECEIPT_INCOMPLETE';Component=$Component;ProcessId=$processId}
        }
        $legacy = Get-ManagedLegacyStateOwnershipEvidence -Worktree $Worktree -Component $Component -State $State -Snapshot $query.Snapshot
        if (-not $legacy.Ready) {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode=$legacy.ReasonCode;Component=$Component;ProcessId=$processId}
        }
        $receiptStart = [datetimeoffset]$legacy.ProcessStartedAt
        $stateGeneration = [string]$legacy.Generation
        $actualFingerprint = [string]$legacy.CommandContractFingerprint
        $receiptId = $null
        $evidenceKind = [string]$legacy.EvidenceKind
    } elseif (-not $receipt.PSObject.Properties['generation'] -or
            -not $receipt.PSObject.Properties['ownershipIdentityFingerprint'] -or
            ($receipt.PSObject.Properties['status'] -and [string]$receipt.status -ne 'READY')) {
        return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='OWNERSHIP_RECEIPT_INCOMPLETE';Component=$Component;ProcessId=$processId}
    } else {
        $stateGeneration = if ($State.PSObject.Properties['serviceGeneration']) { [string]$State.serviceGeneration } else { $null }
        if ([string]::IsNullOrWhiteSpace($stateGeneration) -or [string]$receipt.generation -ne $stateGeneration) {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='GENERATION_MISMATCH';Component=$Component;ProcessId=$processId}
        }
        $receiptStart = [datetimeoffset]::MinValue
        if (-not [datetimeoffset]::TryParse([string]$receipt.processStartedAt, [ref]$receiptStart) -or
                -not (Test-ManagedProcessCanonicalStartTimeEqual -Left $receiptStart `
                    -Right ([datetimeoffset]$query.Snapshot.StartedAtUtc))) {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='PROCESS_START_MISMATCH';Component=$Component;ProcessId=$processId}
        }
        try {
            $actualFingerprint = Get-ManagedProcessCommandFingerprint -Snapshot $query.Snapshot -Worktree $Worktree `
                -Component $Component -OwnershipReceipt $receipt -ExactActiveCoordinationProof
        } catch {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='COMMAND_CONTRACT_MISMATCH';Component=$Component;ProcessId=$processId}
        }
        if (-not (Test-ManagedProcessOwnershipIdentityFingerprint -Snapshot $query.Snapshot -Worktree $Worktree `
                -Component $Component -OwnershipReceipt $receipt -ExactActiveCoordinationProof)) {
            return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='COMMAND_FINGERPRINT_MISMATCH';Component=$Component;ProcessId=$processId}
        }
        $receiptId = [string]$receipt.operationId
        $evidenceKind = 'OWNERSHIP_RECEIPT'
    }
    try { $operations = if ($null -ne $OperationDocuments) { @($OperationDocuments) } else { @(Get-ManagedCoordinationOperations -StateRoot $StateRoot) } }
    catch { return [pscustomobject]@{Classification='ORPHAN_UNVERIFIABLE';ReasonCode='COORDINATION_EVIDENCE_INVALID';Component=$Component;ProcessId=$processId} }
    $active = @($operations | Where-Object { $_.status -eq 'ACTIVE' -and @($_.resources) -contains $definition.Resource })
    if (@($active | Where-Object { [int]$_.ownerPid -ne $processId }).Count -gt 0) {
        return [pscustomobject]@{Classification='ORPHAN_BLOCKED_COMPETING_OWNER';ReasonCode='ACTIVE_COMPETING_OWNER';Component=$Component;ProcessId=$processId}
    }
    if (@($active | Where-Object { [int]$_.ownerPid -eq $processId }).Count -eq 1) {
        return [pscustomobject]@{Classification='MANAGED_ACTIVE';ReasonCode='ACTIVE_OWNER_PRESENT';Component=$Component;ProcessId=$processId}
    }
    return [pscustomobject]@{
        Classification='ORPHAN_EXACT_RECONCILABLE';ReasonCode='EXACT_OWNER_WITHOUT_ACTIVE_OPERATION'
        Component=$Component;ProcessId=$processId;ProcessStartedAt=$receiptStart;Generation=$stateGeneration
        ReceiptId=$receiptId;EvidenceKind=$evidenceKind;ResourceFingerprint=(Get-ManagedTextFingerprint $definition.Resource)
        WorktreeFingerprint=(Get-ManagedTextFingerprint ([IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant()))
        CommandContractFingerprint=$actualFingerprint
    }
}

function New-ManagedOrphanStopAuthority {
    param(
        [Parameter(Mandatory)]$Diagnosis,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [ValidateRange(5,120)][int]$TtlSeconds = 30
    )
    if ([string]$Diagnosis.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') {
        throw 'Only an exact reconciliable orphan diagnosis can issue stop authority.'
    }
    $authorityId = [guid]::NewGuid().ToString()
    $issuedAt = [datetimeoffset]::UtcNow
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
        operationId=$authorityId;outcome='READY';status='OPEN';action='managed-orphan-stop-authority'
        component=[string]$Diagnosis.Component;processId=[int]$Diagnosis.ProcessId
        processStartedAt=([datetimeoffset]$Diagnosis.ProcessStartedAt).ToString('o');generation=[string]$Diagnosis.Generation
        ownershipReceiptId=[string]$Diagnosis.ReceiptId;resourceFingerprint=[string]$Diagnosis.ResourceFingerprint
        evidenceKind=[string]$Diagnosis.EvidenceKind
        worktreeFingerprint=[string]$Diagnosis.WorktreeFingerprint
        commandContractFingerprint=[string]$Diagnosis.CommandContractFingerprint
        issuedAt=$issuedAt.ToString('o');expiresAt=$issuedAt.AddSeconds($TtlSeconds).ToString('o')
        disposition='component-scoped; single-use; evidence-retained'
    })
    return [pscustomobject]@{AuthorityId=$authorityId;Action='managed-orphan-stop-authority';Component=[string]$Diagnosis.Component;ExpiresAt=$issuedAt.AddSeconds($TtlSeconds)}
}

function Test-ManagedOrphanStopAuthority {
    param(
        [Parameter(Mandatory)][string]$AuthorityId,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$State,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id }
    )
    if ($AuthorityId -notmatch '^[a-f0-9-]{36}$') { return [pscustomobject]@{Outcome='REJECTED';ReasonCode='AUTHORITY_ID_INVALID'} }
    $path = Join-Path (Join-Path $StateRoot 'receipts') "$AuthorityId.json"
    try { $authority = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { return [pscustomobject]@{Outcome='REJECTED';ReasonCode='AUTHORITY_INVALID'} }
    if ($authority.action -ne 'managed-orphan-stop-authority' -or $authority.status -ne 'OPEN') {
        $reason = if ($authority.status -eq 'CONSUMED') { 'AUTHORITY_ALREADY_CONSUMED' } else { 'AUTHORITY_INVALID' }
        return [pscustomobject]@{Outcome='REJECTED';ReasonCode=$reason}
    }
    $expiresAt = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse([string]$authority.expiresAt, [ref]$expiresAt) -or $expiresAt -le [datetimeoffset]::UtcNow) {
        return [pscustomobject]@{Outcome='REJECTED';ReasonCode='AUTHORITY_EXPIRED'}
    }
    if ([string]$authority.component -ne $Component) { return [pscustomobject]@{Outcome='REJECTED';ReasonCode='COMPONENT_MISMATCH'} }
    $diagnosis = Get-ManagedOrphanDiagnosis -Worktree $Worktree -Component $Component -State $State `
        -StateRoot $StateRoot -ProcessQuery $ProcessQuery -OperationDocuments @()
    $worktreeFingerprint = Get-ManagedTextFingerprint ([IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant())
    if ($diagnosis.Classification -ne 'ORPHAN_EXACT_RECONCILABLE' -or
            [int]$authority.processId -ne [int]$diagnosis.ProcessId -or
            [string]$authority.processStartedAt -ne ([datetimeoffset]$diagnosis.ProcessStartedAt).ToString('o') -or
            [string]$authority.generation -ne [string]$diagnosis.Generation -or
            [string]$authority.evidenceKind -ne [string]$diagnosis.EvidenceKind -or
            [string]$authority.ownershipReceiptId -ne [string]$diagnosis.ReceiptId -or
            [string]$authority.worktreeFingerprint -ne $worktreeFingerprint -or
            [string]$authority.resourceFingerprint -ne [string]$diagnosis.ResourceFingerprint -or
            [string]$authority.commandContractFingerprint -ne [string]$diagnosis.CommandContractFingerprint) {
        return [pscustomobject]@{Outcome='REJECTED';ReasonCode='AUTHORITY_EVIDENCE_MISMATCH'}
    }
    return [pscustomobject]@{Outcome='READY';ReasonCode=$null;ProcessId=[int]$diagnosis.ProcessId;Component=$Component;AuthorityId=$AuthorityId}
}

function Complete-ManagedOrphanStopAuthority {
    param([Parameter(Mandatory)][string]$AuthorityId,[string]$StateRoot = (Get-CoordinationDefaultRoot))
    if ($AuthorityId -notmatch '^[a-f0-9-]{36}$') { throw 'Managed orphan authority id is invalid.' }
    $path = Join-Path (Join-Path $StateRoot 'receipts') "$AuthorityId.json"
    $authority = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($authority.action -ne 'managed-orphan-stop-authority') { throw 'Managed orphan authority has an invalid typed schema.' }
    if ($authority.status -eq 'CONSUMED') { return }
    if ($authority.status -ne 'OPEN') { throw 'Managed orphan authority is not open.' }
    $authority.status = 'CONSUMED'
    $authority | Add-Member -NotePropertyName consumedAt -NotePropertyValue ([datetimeoffset]::UtcNow.ToString('o')) -Force
    Write-CoordinationJsonAtomic -Path $path -Document $authority
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
    if (-not $operation -and $trustedReceipt) {
        return [pscustomobject]@{
            Outcome='BLOCKED';Reason='Exact durable ownership exists without an active coordination operation.'
            ReasonCode='EXACT_OWNER_WITHOUT_ACTIVE_OPERATION';Classification='ORPHAN_EXACT_RECONCILABLE'
            Definition=$definition;ProcessId=$processId
        }
    }
    return [pscustomobject]@{Outcome='READY';Disposition='OWNED_RUNNING';Definition=$definition;ProcessId=$processId;ProcessPresent=$true;Snapshot=$snapshot;Operation=$operation;Receipt=$receipt}
}

function Update-ManagedDevStateAtomic {
    param(
        [Parameter(Mandatory)][string]$StateFile,
        [Parameter(Mandatory)]$Definition,
        [Parameter(Mandatory)][int]$ExpectedProcessId
    )
    $guard = New-CoordinationMutex "service-state/$StateFile"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $guard -Deadline ([datetime]::UtcNow.AddSeconds(10))
        if (-not $held) { throw 'Development state is BUSY; component ownership was not changed.' }
        $state = [IO.File]::ReadAllText($StateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $currentProperty = $state.PSObject.Properties[$Definition.StateField]
        $currentProcessId = if ($currentProperty -and $currentProperty.Value) { [int]$currentProperty.Value } else { 0 }
        if ($currentProcessId -eq 0) { return }
        if ($currentProcessId -ne $ExpectedProcessId) {
            throw 'Managed component state changed to another process generation; writeback refused.'
        }
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
    Update-ManagedDevStateAtomic -StateFile $StateFile -Definition $Proof.Definition -ExpectedProcessId ([int]$Proof.ProcessId)
    $receiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
        operationId=$receiptId;outcome='READY';action='managed-process-stop';component=$Proof.Definition.Component
        processId=$Proof.ProcessId;worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\')
        resource=$Proof.Definition.Resource;disposition='evidence-retained; component-state-cleared'
    })
    return $receiptId
}

function Stop-ManagedExactProcessTree {
    param(
        [Parameter(Mandatory)][int]$RootProcessId,
        [int]$ExpectedPort = 0,
        [scriptblock]$ProcessTableAdapter = {
            try {
                $rows = @(Get-CimInstance Win32_Process -ErrorAction Stop | Select-Object ProcessId,ParentProcessId)
                [pscustomobject]@{Outcome='READY';Processes=$rows}
            } catch {
                $denied = $_.Exception -is [UnauthorizedAccessException] -or $_.FullyQualifiedErrorId -match 'AccessDenied|UnauthorizedAccess'
                [pscustomobject]@{Outcome=if($denied){'CALLER_ACCESS_DENIED'}else{'UNKNOWN'};Processes=@()}
            }
        },
        [scriptblock]$TerminationAdapter = {
            param($id)
            try { Stop-Process -Id $id -Force -ErrorAction Stop;[pscustomobject]@{Success=$true} }
            catch [ArgumentException] { [pscustomobject]@{Success=$true} }
            catch { [pscustomobject]@{Success=$false} }
        },
        [scriptblock]$VerificationQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [scriptblock]$PortObservationAdapter,
        [ValidateRange(1,30)][int]$TimeoutSeconds = 15
    )
    $table = & $ProcessTableAdapter
    if (-not $table -or $table.Outcome -ne 'READY') {
        $reasonCode = if ($table) { "PROCESS_TREE_$($table.Outcome)" } else { 'PROCESS_TREE_UNKNOWN' }
        return [pscustomobject]@{Success=$false;ReasonCode=$reasonCode;StoppedCount=0}
    }
    $descendants = [Collections.Generic.List[int]]::new()
    $frontier = [Collections.Generic.List[int]]::new()
    $frontier.Add($RootProcessId)
    for ($index=0;$index -lt $frontier.Count;$index++) {
        $parent = $frontier[$index]
        foreach ($row in @($table.Processes | Where-Object { [int]$_.ParentProcessId -eq $parent })) {
            $child = [int]$row.ProcessId
            if ($child -gt 0 -and $child -ne $RootProcessId -and -not $descendants.Contains($child)) {
                $descendants.Add($child);$frontier.Add($child)
            }
        }
    }
    $ordered = @($descendants.ToArray());[array]::Reverse($ordered);$ordered += $RootProcessId
    foreach ($processId in $ordered) {
        $stopped = & $TerminationAdapter $processId
        if (-not $stopped -or -not [bool]$stopped.Success) {
            return [pscustomobject]@{Success=$false;ReasonCode='EXACT_PROCESS_TERMINATION_FAILED';StoppedCount=0}
        }
    }
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $rootQuery = & $VerificationQuery $RootProcessId
        $rootDown = (Get-ManagedProcessObservation -QueryResult $rootQuery).State -eq 'DOWN'
        $portDown = $true
        if ($ExpectedPort -gt 0) {
            $portObservation = if ($PortObservationAdapter) { & $PortObservationAdapter $ExpectedPort } else {
                try {
                    $owner = Get-NetTCPConnection -LocalPort $ExpectedPort -State Listen -ErrorAction Stop | Select-Object -First 1
                    [pscustomobject]@{State=if($owner){'UP'}else{'DOWN'}}
                } catch { [pscustomobject]@{State='UNKNOWN'} }
            }
            $portDown = $portObservation.State -eq 'DOWN'
        }
        if ($rootDown -and $portDown) { return [pscustomobject]@{Success=$true;ReasonCode=$null;StoppedCount=$ordered.Count} }
        Start-Sleep -Milliseconds 100
    } while ([datetimeoffset]::UtcNow -lt $deadline)
    return [pscustomobject]@{Success=$false;ReasonCode='EXACT_PROCESS_STOP_NOT_VERIFIED';StoppedCount=0}
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
    $orphanAuthorityId = $null
    if ($proof.Outcome -ne 'READY') {
        $diagnosis = Get-ManagedOrphanDiagnosis -Worktree $Worktree -Component $Component -State $state `
            -StateRoot $StateRoot -ProcessQuery $ProcessQuery -OperationDocuments $OperationDocuments
        if ($diagnosis.Classification -ne 'ORPHAN_EXACT_RECONCILABLE') {
            return [pscustomobject]@{
                Outcome='BLOCKED';Reason='Managed component ownership requires typed orphan reconciliation.'
                ReasonCode=[string]$diagnosis.ReasonCode;Classification=[string]$diagnosis.Classification
                Component=$Component;ProcessId=$diagnosis.ProcessId
            }
        }
        $authority = New-ManagedOrphanStopAuthority -Diagnosis $diagnosis -StateRoot $StateRoot
        $validated = Test-ManagedOrphanStopAuthority -AuthorityId $authority.AuthorityId -Worktree $Worktree `
            -Component $Component -State $state -StateRoot $StateRoot -ProcessQuery $ProcessQuery
        if ($validated.Outcome -ne 'READY') {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='Managed orphan stop authority failed exact revalidation.';ReasonCode=$validated.ReasonCode;Component=$Component}
        }
        $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
        $current = & $ProcessQuery ([int]$diagnosis.ProcessId)
        $proof = [pscustomobject]@{
            Outcome='READY';Disposition='ORPHAN_EXACT_RECONCILE';Definition=$definition
            ProcessId=[int]$diagnosis.ProcessId;ProcessPresent=$true;Snapshot=$current.Snapshot
            Operation=$null;Receipt=$null;AuthorityId=$authority.AuthorityId
        }
        $orphanAuthorityId = $authority.AuthorityId
    }
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
    if ($orphanAuthorityId) { Complete-ManagedOrphanStopAuthority -AuthorityId $orphanAuthorityId -StateRoot $StateRoot }
    return [pscustomobject]@{Outcome='READY';Disposition=$proof.Disposition;Component=$Component;Changed=$true;ReceiptId=$receiptId;Proof=$proof}
}

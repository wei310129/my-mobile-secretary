[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')

$assertions = 0
$started = [datetime]::UtcNow.AddSeconds(-1)
$exact = [pscustomobject]@{
    ProcessId=811;ParentProcessId=1;Name='ngrok.exe';ExecutablePath='C:\tools\ngrok.exe'
    CommandLine='"C:\tools\ngrok.exe" http --url=safe.example.invalid http://localhost:8080 --log=stdout --log-level=warn'
    StartedAtUtc=$started;QueryKind='NATIVE_EXACT'
}

function Assert-ExactQuery([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Copy-ExactSnapshot {
    param([hashtable]$Overrides = @{})
    $copy = [ordered]@{}
    foreach ($property in $exact.PSObject.Properties) { $copy[$property.Name] = $property.Value }
    foreach ($key in $Overrides.Keys) { $copy[$key] = $Overrides[$key] }
    return [pscustomobject]$copy
}

function Invoke-CombinedQuery($WmiResult, $NativeResult, [int]$ProcessId = 811) {
    $wmi = { param($id,$timeout); return $WmiResult }.GetNewClosure()
    $native = { param($id); return $NativeResult }.GetNewClosure()
    return Get-ManagedProcessQueryResult -ProcessId $ProcessId -TimeoutMilliseconds 500 `
        -WmiQuery $wmi -NativeQuery $native
}

$limited = [pscustomobject]@{
    Outcome='READY';Snapshot=[pscustomobject]@{
        ProcessId=811;ParentProcessId=0;Name='ngrok.exe';ExecutablePath=$null;CommandLine=$null
        StartedAtUtc=$started;QueryKind='LIMITED_NATIVE'
    }
}
$unavailable = [pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='WMI_QUERY_UNAVAILABLE'}
$nativeReady = [pscustomobject]@{Outcome='READY';Snapshot=$exact}

$liveEquivalent = Invoke-CombinedQuery $limited $nativeReady
Assert-ExactQuery ($liveEquivalent.Outcome -eq 'READY' -and
        $liveEquivalent.Snapshot.QueryKind -eq 'NATIVE_EXACT' -and
        $liveEquivalent.Snapshot.ExecutablePath -eq 'C:\tools\ngrok.exe' -and
        $liveEquivalent.Snapshot.CommandLine -match 'localhost:8080') `
    'the live-equivalent LIMITED_NATIVE WMI result did not fall back to an exact native snapshot'

$wmiDenied = Invoke-CombinedQuery $unavailable $nativeReady
Assert-ExactQuery ($wmiDenied.Outcome -eq 'READY' -and $wmiDenied.Snapshot.QueryKind -eq 'NATIVE_EXACT') `
    'WMI access denial did not allow the exact native source to provide identity'

$wmiExact = [pscustomobject]@{Outcome='READY';Snapshot=(Copy-ExactSnapshot @{QueryKind='WMI_EXACT'})}
$consensus = Invoke-CombinedQuery $wmiExact $nativeReady
Assert-ExactQuery ($consensus.Outcome -eq 'READY' -and $consensus.Snapshot.QueryKind -eq 'WMI_NATIVE_CONSENSUS') `
    'matching WMI and native exact snapshots did not produce consensus'

foreach ($case in @(
    @{Label='executable';Snapshot=(Copy-ExactSnapshot @{ExecutablePath='C:\other\ngrok.exe'})},
    @{Label='command';Snapshot=(Copy-ExactSnapshot @{CommandLine='"C:\tools\ngrok.exe" http http://localhost:9999'})},
    @{Label='PID';Snapshot=(Copy-ExactSnapshot @{ProcessId=812})},
    @{Label='start time';Snapshot=(Copy-ExactSnapshot @{StartedAtUtc=$started.AddMinutes(-2)})}
)) {
    $mismatch = Invoke-CombinedQuery $wmiExact ([pscustomobject]@{Outcome='READY';Snapshot=$case.Snapshot})
    Assert-ExactQuery ($mismatch.Outcome -eq 'UNKNOWN' -and $mismatch.ReasonCode -eq 'PROCESS_IDENTITY_SOURCE_MISMATCH' -and
            $null -eq $mismatch.Snapshot) "WMI/native $($case.Label) mismatch did not fail closed"
}

$wmiOnly = Invoke-CombinedQuery $wmiExact ([pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='NATIVE_QUERY_DENIED'})
Assert-ExactQuery ($wmiOnly.Outcome -eq 'READY' -and $wmiOnly.Snapshot.QueryKind -eq 'WMI_EXACT') `
    'an exact WMI snapshot was rejected solely because the alternate source was unavailable'

$bothMissing = Invoke-CombinedQuery ([pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}) `
    ([pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null})
Assert-ExactQuery ($bothMissing.Outcome -eq 'NOT_FOUND' -and $null -eq $bothMissing.Snapshot) `
    'two NOT_FOUND sources did not retain the not-running classification'

$nativeMissing = Invoke-CombinedQuery $unavailable ([pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null})
Assert-ExactQuery ($nativeMissing.Outcome -eq 'NOT_FOUND' -and $null -eq $nativeMissing.Snapshot) `
    'native NOT_FOUND was not authoritative when WMI was unavailable'

$bothUnavailable = Invoke-CombinedQuery $unavailable `
    ([pscustomobject]@{Outcome='UNAVAILABLE';Snapshot=$null;ReasonCode='NATIVE_QUERY_DENIED'})
$serializedFailure = $bothUnavailable | ConvertTo-Json -Compress
Assert-ExactQuery ($bothUnavailable.Outcome -eq 'UNKNOWN' -and
        $serializedFailure -notmatch 'safe\.example|C:\\tools|CommandLine') `
    'unavailable identity sources leaked command/path data or did not fail closed'

if ($env:OS -eq 'Windows_NT') {
    $nativeSelf = Get-NativeManagedProcessQueryResult -ProcessId $PID
    Assert-ExactQuery ($nativeSelf.Outcome -eq 'READY' -and
            -not [string]::IsNullOrWhiteSpace([string]$nativeSelf.Snapshot.ExecutablePath) -and
            -not [string]::IsNullOrWhiteSpace([string]$nativeSelf.Snapshot.CommandLine) -and
            $nativeSelf.Snapshot.StartedAtUtc) `
        'the Windows-native source could not obtain an exact snapshot for the current same-caller process'
} else {
    Assert-ExactQuery $true 'non-Windows runner uses fixture coverage for the Windows-native source'
}

[pscustomobject]@{status='passed';assertions=$assertions;liveEquivalent='same-caller-read-only';sensitiveOutput='none'} |
    ConvertTo-Json -Compress

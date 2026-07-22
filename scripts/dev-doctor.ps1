[CmdletBinding()]
param(
    [switch]$Json,
    [switch]$NoExternalProbe,
    [string]$StateRoot
)

. "$PSScriptRoot\_devops-common.ps1"
if (-not $NoExternalProbe) { throw 'External probes are opt-in only; use -NoExternalProbe for the read-only coordinator doctor.' }
$arguments = @{}
if ($StateRoot) { $arguments.StateRoot = $StateRoot }
$snapshot = Get-CoordinationDoctorSnapshot @arguments
$state = Read-DevState
$unmanaged = @()
foreach ($check in @(
        @{ Name = 'springBoot'; Pid = $state.springBootPid; Port = $AppPort },
        @{ Name = 'dispatcher'; Pid = $state.dispatcherPid; Port = $DispatcherPort },
        @{ Name = 'ngrok'; Pid = $state.ngrokPid; Port = $NgrokApiPort })) {
    $owner = Get-PortOwnerPid -Port $check.Port
    if ($owner -and $owner -ne $check.Pid) { $unmanaged += "$($check.Name):PID=$owner" }
}
$unmanaged += Get-UnmanagedDevelopmentWriters -State $state
$unmanaged = @($unmanaged | Select-Object -Unique)
if ($unmanaged.Count -gt 0) {
    $snapshot.outcome = 'BLOCKED'; $snapshot.exitCode = 40
    $snapshot | Add-Member -NotePropertyName unmanagedWriters -NotePropertyValue $unmanaged -Force
}
if ($Json) { $snapshot | ConvertTo-Json -Depth 8 }
else { Write-Host "Coordinator doctor: $($snapshot.outcome); registryPresent=$($snapshot.registryPresent); externalProbe=$($snapshot.externalProbe)" }
exit [int]$snapshot.exitCode

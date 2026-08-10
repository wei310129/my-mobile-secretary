[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$scripts=Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts '_devops-common.ps1')
$assertions=0
function Assert-StatusClass([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:assertions++}

$up=Get-DevPortObservation -Port 8080 -QueryAdapter {param($port)[pscustomobject]@{Outcome='READY';ProcessId=101}}
$down=Get-DevPortObservation -Port 8080 -QueryAdapter {param($port)[pscustomobject]@{Outcome='NOT_FOUND';ProcessId=$null}}
$denied=Get-DevPortObservation -Port 8080 -QueryAdapter {param($port)[pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';ProcessId=$null}}
$unknown=Get-DevPortObservation -Port 8080 -QueryAdapter {param($port)[pscustomobject]@{Outcome='UNKNOWN';ProcessId=$null}}
Assert-StatusClass ($up.State -eq 'UP' -and $up.ProcessId -eq 101) 'listening port was not classified UP'
Assert-StatusClass ($down.State -eq 'DOWN' -and $null -eq $down.ProcessId) 'proven absent port was not classified DOWN'
Assert-StatusClass ($denied.State -eq 'CALLER_ACCESS_DENIED') 'port caller denial was collapsed into DOWN or UNKNOWN'
Assert-StatusClass ($unknown.State -eq 'UNKNOWN') 'unknown port observation was collapsed into DOWN'

foreach($callerCase in @(
    @{Caller='WINDOWS_SANDBOX';Result=[pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';ReasonCode='PROCESS_QUERY_ACCESS_DENIED'};Expected='CALLER_ACCESS_DENIED'},
    @{Caller='WINDOWS_REQUIRE_ESCALATED_HOST';Result=[pscustomobject]@{Outcome='READY';Snapshot=[pscustomobject]@{ProcessId=101}};Expected='UP'},
    @{Caller='WSL';Result=[pscustomobject]@{Outcome='UNKNOWN';ReasonCode='PROCESS_IDENTITY_UNAVAILABLE'};Expected='UNKNOWN'}
)) {
    $callerObservation=Get-ManagedProcessObservation -QueryResult $callerCase.Result
    Assert-StatusClass ($callerObservation.State -eq $callerCase.Expected) `
        "$($callerCase.Caller) observation did not preserve $($callerCase.Expected)"
}

$dockerDenied=Get-DevInfrastructureObservation -Result ([pscustomobject]@{
    Outcome='BLOCKED';Classification='CALLER_ACCESS_DENIED';Readiness=[pscustomobject]@{Outcome='BLOCKED'};Containers=@()
})
$dockerUnknown=Get-DevInfrastructureObservation -Result ([pscustomobject]@{
    Outcome='BLOCKED';Classification='DAEMON_STATE_UNKNOWN';Readiness=[pscustomobject]@{Outcome='BLOCKED'};Containers=@()
})
$dockerDown=Get-DevInfrastructureObservation -Result ([pscustomobject]@{
    Outcome='BLOCKED';Classification='DAEMON_NOT_READY';Readiness=[pscustomobject]@{Outcome='BLOCKED'};Containers=@()
})
Assert-StatusClass ($dockerDenied.State -eq 'CALLER_ACCESS_DENIED') 'Docker caller denial was collapsed into not running'
Assert-StatusClass ($dockerUnknown.State -eq 'UNKNOWN') 'unknown Docker state was collapsed into not running'
Assert-StatusClass ($dockerDown.State -eq 'DOWN') 'proven Docker daemon down was not classified DOWN'

$statusText=[IO.File]::ReadAllText((Join-Path $scripts 'dev-status.ps1'),[Text.Encoding]::UTF8)
Assert-StatusClass ($statusText.Contains('Get-DevInfrastructureObservation') -and $statusText.Contains('CALLER_ACCESS_DENIED')) `
    'dev-status does not render typed infrastructure caller denial'
Assert-StatusClass ($statusText.Contains('Get-ManagedProcessObservation') -and $statusText.Contains('UNKNOWN')) `
    'dev-status does not render typed managed-process uncertainty'
Assert-StatusClass (-not ($statusText -match '\$pgDisplay\s*=\s*if\s*\(\$pgStatus\).*?else\s*\{\s*"not running"')) `
    'dev-status still renders every unobservable PostgreSQL state as not running'

$serialized=@($denied,$unknown,$dockerDenied,$dockerUnknown)|ConvertTo-Json -Compress -Depth 5
Assert-StatusClass ($serialized -notmatch 'hostname|username|C:\\|/home/|CommandLine|webhook') `
    'typed status failures leaked host, account, path, command, or webhook data'

[pscustomobject]@{status='passed';assertions=$assertions;externalQueries=0;externalMutations=0}|ConvertTo-Json -Compress

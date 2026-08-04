[CmdletBinding(SupportsShouldProcess)]
param(
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,31}$')][string]$MachineAlias,
    [switch]$PublishGitHub,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$context = Get-EnvironmentStateContext -RepoRoot $repoRoot -MachineAlias $MachineAlias
if ($MachineAlias) { Set-EnvironmentMachineAlias -MachineAlias $MachineAlias -StateRoot $context.StateRoot | Out-Null }
$taskName = "MMS-Environment-Monitor-$($context.RepoId)"
$periodicTaskName = "$taskName-Periodic"
$logonTaskName = "$taskName-Logon"
if ($Uninstall) {
    if ($PSCmdlet.ShouldProcess($taskName, 'unregister scheduled task')) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
        & schtasks.exe /Delete /TN $periodicTaskName /F 2>$null | Out-Null
        & schtasks.exe /Delete /TN $logonTaskName /F 2>$null | Out-Null
    }
    exit 0
}
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSScriptRoot\dev-environment-monitor.ps1`" -Scheduled -Once -AllowSafeRepair"
if ($PublishGitHub) { $arguments += ' -PublishGitHub' }
$registration = 'NotChanged'
$logonReady = $false
if ($PSCmdlet.ShouldProcess($taskName, 'register adaptive environment monitor')) {
    $registration = 'ScheduledTasks'
    try {
        $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments -WorkingDirectory $repoRoot
        $logon = New-ScheduledTaskTrigger -AtLogOn
        $periodic = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
            -RepetitionInterval (New-TimeSpan -Minutes 15) -RepetitionDuration (New-TimeSpan -Days 3650)
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
            -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -StartWhenAvailable
        $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
            -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger @($logon,$periodic) `
            -Settings $settings -Principal $principal `
            -Description 'Low-cost my-mobile-secretary environment monitor; heavy probes run only for active demand.' `
            -Force -ErrorAction Stop | Out-Null
        $logonReady = $true
        Invoke-EnvironmentProcess -FilePath 'schtasks.exe' -Arguments @('/Delete','/TN',$periodicTaskName,'/F') -TimeoutMilliseconds 5000 | Out-Null
        Invoke-EnvironmentProcess -FilePath 'schtasks.exe' -Arguments @('/Delete','/TN',$logonTaskName,'/F') -TimeoutMilliseconds 5000 | Out-Null
    } catch {
        # Some standard-user Windows installations deny CIM task registration with an
        # explicit principal but permit a limited current-user periodic task through SCHTASKS.
        $registration = 'SchtasksPeriodicFallback'
        $run = "powershell.exe $arguments"
        $periodicResult = Invoke-EnvironmentProcess -FilePath 'schtasks.exe' `
            -Arguments @('/Create','/TN',$periodicTaskName,'/SC','MINUTE','/MO','15','/TR',$run,'/F','/RL','LIMITED') `
            -TimeoutMilliseconds 15000
        if ($periodicResult.ExitCode -ne 0) {
            Write-EnvironmentIssue -Code 'SCHEDULED_MONITOR_REGISTRATION_FAILED' -Capability READ_ONLY `
                -Expected 'a limited current-user periodic environment monitor is registered' `
                -Actual 'both ScheduledTasks and SCHTASKS periodic registration were denied' `
                -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
            throw 'Both ScheduledTasks and SCHTASKS periodic registration were denied.'
        }
        Invoke-EnvironmentProcess -FilePath 'schtasks.exe' -Arguments @('/Delete','/TN',$taskName,'/F') -TimeoutMilliseconds 5000 | Out-Null
        $logonResult = Invoke-EnvironmentProcess -FilePath 'schtasks.exe' `
            -Arguments @('/Create','/TN',$logonTaskName,'/SC','ONLOGON','/TR',$run,'/F','/RL','LIMITED') `
            -TimeoutMilliseconds 15000
        if ($logonResult.ExitCode -ne 0) {
            Write-EnvironmentIssue -Code 'SCHEDULED_LOGON_TRIGGER_DENIED' -Capability READ_ONLY `
                -Expected 'environment monitor also refreshes at interactive logon' `
                -Actual 'current-user ONLOGON task registration was denied' -Status ACCEPTED_LIMITATION `
                -ResolutionEvidence 'session async preflight plus the 15-minute limited periodic task remains active' `
                -RepoRoot $repoRoot -StateRoot $context.StateRoot -MachineAlias $context.MachineAlias | Out-Null
            Write-Host 'Logon trigger was denied; session preflight plus the 15-minute periodic task remains active.' -ForegroundColor Yellow
        } else { $logonReady = $true }
    }
    $receipt = [ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;operationId="monitor-install-$([guid]::NewGuid())"
        outcome='READY';registration=$registration;periodicReady=$true;logonReady=$logonReady
        intervalMinutes=15;safeRepairEnabled=$true;githubPublishEnabled=[bool]$PublishGitHub
        at=[datetime]::UtcNow.ToString('o')
    }
    Write-CoordinationJsonAtomic -Path (Join-Path $context.ReceiptsPath "$($receipt.operationId).json") -Document $receipt
}
Write-Host "Scheduled environment monitor ready: $taskName ($registration)"

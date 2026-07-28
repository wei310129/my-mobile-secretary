[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) '_devops-common.ps1')
function Assert-Doctor([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

$state = [pscustomobject]@{ springBootPid = 100; dispatcherPid = 0; ngrokPid = 0 }
$processes = @(
    [pscustomobject]@{ ProcessId = 100; ParentProcessId = 1; CommandLine = 'powershell coordinated-maven-run.ps1 -Application root' },
    [pscustomobject]@{ ProcessId = 101; ParentProcessId = 100; CommandLine = 'cmd /c mvnw.cmd spring-boot:run' },
    [pscustomobject]@{ ProcessId = 200; ParentProcessId = 1; CommandLine = 'cmd /c mvnw.cmd test' },
    [pscustomobject]@{ ProcessId = 201; ParentProcessId = 1; CommandLine = 'docker compose up -d' },
    [pscustomobject]@{ ProcessId = 300; ParentProcessId = 1; CommandLine = 'notepad.exe' }
)
$unmanaged = @(Get-UnmanagedDevelopmentWriters -State $state -Processes $processes)
Assert-Doctor ($unmanaged.Count -eq 2) 'doctor did not isolate unmanaged writer processes'
Assert-Doctor ($unmanaged -contains 'process:PID=200') 'doctor missed direct Maven writer'
Assert-Doctor ($unmanaged -contains 'process:PID=201') 'doctor missed direct Compose writer'
[pscustomobject]@{ status = 'passed'; assertions = 3; livePaths = 'skipped' } | ConvertTo-Json -Compress

<#
.SYNOPSIS
  Runs one root-project Maven lifecycle at a time without cleaning by default.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 '-Dtest=ReceiptServiceTest' test

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -Clean test
#>

[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Clean,
    [ValidateRange(1, 3600)]
    [int]$LockTimeoutSeconds = 300,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_maven-quiet.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repoRoot 'mvnw.cmd'
$mutexName = 'Local\my-mobile-secretary-root-maven-lifecycle'
$mutex = [System.Threading.Mutex]::new($false, $mutexName)
$lockAcquired = $false
$exitCode = 1

try {
    try {
        $lockAcquired = $mutex.WaitOne([TimeSpan]::FromSeconds($LockTimeoutSeconds))
    } catch [System.Threading.AbandonedMutexException] {
        # The previous owner exited unexpectedly. The OS released the lock for this process.
        $lockAcquired = $true
    }
    if (-not $lockAcquired) {
        throw "等待 Maven 執行鎖超過 $LockTimeoutSeconds 秒；另一個根專案 Maven lifecycle 可能仍在執行。"
    }
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "找不到 Maven Wrapper：$mavenWrapper"
    }
    if (-not $MavenArguments -or $MavenArguments.Count -eq 0) {
        throw '請提供 Maven goal，例如 test 或 package。'
    }

    # Windows treats environment keys case-insensitively. Keep one canonical Path entry so
    # cmd.exe/Java children receive a clean environment block.
    $processPath = [System.Environment]::GetEnvironmentVariable('Path', 'Process')
    if ($processPath) {
        [System.Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
        [System.Environment]::SetEnvironmentVariable('Path', $processPath, 'Process')
    }

    $arguments = [System.Collections.Generic.List[string]]::new()
    if ($Clean) {
        $arguments.Add('clean')
    }
    foreach ($argument in $MavenArguments) {
        $arguments.Add($argument)
    }

    $exitCode = Invoke-QuietMaven `
        -Arguments $arguments.ToArray() `
        -SuccessMessage 'Maven 成功'
} catch {
    Write-Output ("Maven runner 失敗：{0}" -f $_.Exception.Message)
    $exitCode = 1
} finally {
    if ($lockAcquired) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}

exit $exitCode

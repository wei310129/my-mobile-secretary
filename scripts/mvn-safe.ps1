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
. "$PSScriptRoot\coordination-maven.ps1"
. "$PSScriptRoot\project-tool-policy.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repoRoot 'mvnw.cmd'
$exitCode = 1

try {
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "找不到 Maven Wrapper：$mavenWrapper"
    }
    if (-not $MavenArguments -or $MavenArguments.Count -eq 0) {
        throw '請提供 Maven goal，例如 test 或 package。'
    }
    Assert-RepositoryMavenInvocation -RepoRoot $repoRoot -Arguments $MavenArguments

    # Windows treats environment keys case-insensitively. Keep one canonical Path entry so
    # cmd.exe/Java children receive a clean environment block.
    $processPath = [System.Environment]::GetEnvironmentVariable('Path', 'Process')
    if ($processPath) {
        [System.Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
        [System.Environment]::SetEnvironmentVariable('Path', $processPath, 'Process')
    }

    $mavenInvocationArguments = [System.Collections.Generic.List[string]]::new()
    if ($Clean) {
        $mavenInvocationArguments.Add('clean')
    }
    foreach ($argument in $MavenArguments) {
        $mavenInvocationArguments.Add($argument)
    }

    $operation = if ($Clean) { 'Clean' } elseif ($MavenArguments -match 'test') { 'Test' } else { 'Build' }
    $coordination = Invoke-CoordinatedMavenOperation `
        -Application root `
        -Worktree $repoRoot `
        -Operation $operation `
        -TimeoutSeconds $LockTimeoutSeconds `
        -Runner { Invoke-QuietMaven -Arguments $mavenInvocationArguments.ToArray() -SuccessMessage 'Maven 成功' }
    if ($coordination.Outcome -eq 'BUSY') {
        throw "等待 Maven 協調租約超過 $LockTimeoutSeconds 秒；另一個根專案 Maven writer 仍在執行。"
    }
    $exitCode = [int]$coordination.ExitCode
} catch {
    Write-Output ("Maven runner 失敗：{0}" -f $_.Exception.Message)
    $exitCode = 1
}

exit $exitCode

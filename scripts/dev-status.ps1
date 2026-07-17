<#
.SYNOPSIS
  快速檢查開發環境目前狀態,不動任何東西(給「現在是不是在跑」這類問題用,省得每次重新探索)。
#>
param([switch]$NoNgrokRequired)

. "$PSScriptRoot\_devops-common.ps1"

Write-Host "=== 開發環境狀態 ===" -ForegroundColor Cyan

# Docker
$allHealthy = $true
$dockerAvailable = (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-DockerDaemon)
$pgStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-postgres" } else { $null }
$redisStatus = if ($dockerAvailable) { Get-ContainerHealth -ContainerName "mms-redis" } else { $null }
Write-Host ("Postgres:    {0}" -f ($(if ($pgStatus) { $pgStatus } else { "未啟動" })))
Write-Host ("Redis:       {0}" -f ($(if ($redisStatus) { $redisStatus } else { "未啟動" })))
if ($pgStatus -ne "healthy" -or $redisStatus -ne "healthy") { $allHealthy = $false }

# Spring Boot
$appPortPid = Get-PortOwnerPid -Port $AppPort
if ($appPortPid) {
    $health = try { (Invoke-RestMethod -Uri "http://localhost:$AppPort/actuator/health" -TimeoutSec 3).status } catch { "無回應" }
    $managed = Test-ManagedProcess -ProcessId $appPortPid -Kind "SpringBoot"
    $color = if ($health -eq "UP" -and $managed) { "Green" } else { "Yellow" }
    Write-Host ("Spring Boot: 運行中(PID $appPortPid,health=$health,managed=$managed)") -ForegroundColor $color
    if ($health -ne "UP" -or -not $managed) { $allHealthy = $false }
} else {
    Write-Host "Spring Boot: 未啟動" -ForegroundColor DarkGray
    $allHealthy = $false
}

# ngrok
$ngrokPortPid = Get-PortOwnerPid -Port $NgrokApiPort
if ($ngrokPortPid) {
    $url = Get-NgrokPublicUrl -TimeoutSec 5
    Write-Host ("ngrok:       運行中(PID $ngrokPortPid)") -ForegroundColor Green
    if ($url) { Write-Host ("  webhook:   $url/api/line/webhook") }
} else {
    Write-Host "ngrok:       未啟動" -ForegroundColor DarkGray
    if (-not $NoNgrokRequired) { $allHealthy = $false }
}

$state = Read-DevState
if ($state.startedAt) {
    Write-Host ""
    Write-Host "上次 dev-start.ps1 啟動時間: $($state.startedAt)" -ForegroundColor DarkGray
}

if (-not $allHealthy) { exit 1 }
exit 0

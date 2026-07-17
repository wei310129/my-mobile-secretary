<#
.SYNOPSIS
  停止本機開發環境:Spring Boot、ngrok,預設保留 Docker(Postgres/Redis)繼續跑。

.PARAMETER Docker
  一併停掉 docker compose 服務(保留資料卷,只是停容器)。

.PARAMETER RemoveVolumes
  必須搭配 -Docker;連資料卷一起刪(本機開發資料庫會清空,不可逆)。

.EXAMPLE
  .\dev-stop.ps1
  .\dev-stop.ps1 -Docker
#>
param(
    [switch]$Docker,
    [switch]$RemoveVolumes
)

. "$PSScriptRoot\_devops-common.ps1"
Set-Location $RepoRoot

if ($RemoveVolumes -and -not $Docker) {
    Write-Host "-RemoveVolumes 必須明確搭配 -Docker；未執行任何停止操作。" -ForegroundColor Red
    exit 1
}

Write-Host "=== 停止開發環境 ===" -ForegroundColor Cyan

$state = Read-DevState

# Spring Boot:先用狀態檔的 PID,沒有或已死就用「誰在聽 8080」保底。
$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot"
} else {
    $portOwner = Get-PortOwnerPid -Port $AppPort
    if ($portOwner) {
        Write-Host "  Spring Boot: port $AppPort 是未受管理的 PID $portOwner，為避免誤殺已跳過。" -ForegroundColor Yellow
    } else {
        Write-Host "  Spring Boot: 沒偵測到在跑。" -ForegroundColor DarkGray
    }
}

# ngrok:同樣先狀態檔,保底用「誰在聽 4040(ngrok 本機 API)」。
$ngrokPid = Resolve-ManagedProcessId -TrackedProcessId $state.ngrokPid `
    -Port $NgrokApiPort -Kind "Ngrok"
if ($ngrokPid) {
    Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok"
} else {
    Write-Host "  ngrok: 沒偵測到在跑。" -ForegroundColor DarkGray
}

Write-DevState -Updates @{ springBootPid = $null; ngrokPid = $null; ngrokUrl = $null }

if ($Docker) {
    Assert-CommandAvailable -Name "docker"
    if ($RemoveVolumes) {
        Write-Host "  docker compose down -v(含資料卷,本機資料庫會清空)..." -ForegroundColor Red
        docker compose down -v
    } else {
        Write-Host "  docker compose stop(保留資料卷)..." -ForegroundColor Yellow
        docker compose stop
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker compose 停止失敗。" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  Docker(Postgres/Redis)維持運行;要一併停用 -Docker。" -ForegroundColor DarkGray
}

Write-Host "=== 已停止 ===" -ForegroundColor Cyan

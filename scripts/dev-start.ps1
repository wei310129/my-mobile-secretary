<#
.SYNOPSIS
  一鍵啟動本機開發環境:Docker(Postgres/Redis) → ngrok 隧道 → Spring Boot。

.PARAMETER Profile
  Spring profile,預設 local(桌面通知、連 compose 起的 DB)。

.PARAMETER NoNgrok
  跳過 ngrok,只在本機測試(不需要 LINE 真的能打進來時用)。

.PARAMETER SkipDocker
  跳過 docker compose(假設 Postgres/Redis 已經在跑)。

.EXAMPLE
  .\dev-start.ps1
  .\dev-start.ps1 -NoNgrok
#>
param(
    [string]$Profile = "local",
    [switch]$NoNgrok,
    [switch]$SkipDocker
)

. "$PSScriptRoot\_devops-common.ps1"
Ensure-LogsDir
Set-Location $RepoRoot

try {
    Assert-CommandAvailable -Name "docker"
    if (-not (Test-Path "$RepoRoot\mvnw.cmd")) { throw "找不到 $RepoRoot\mvnw.cmd" }
    if (-not (Test-DockerDaemon)) { throw "Docker daemon 尚未啟動，請先開啟 Docker Desktop。" }
    Assert-PortAvailableOrManaged -Port $AppPort -Kind "SpringBoot"
    if (-not $NoNgrok) { Assert-PortAvailableOrManaged -Port $NgrokApiPort -Kind "Ngrok" }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

Write-Host "=== 啟動開發環境(profile=$Profile) ===" -ForegroundColor Cyan

# 1) Docker:Postgres + Redis -----------------------------------------------
if (-not $SkipDocker) {
    Write-Host "[1/3] docker compose up -d ..." -ForegroundColor Yellow
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Host "docker compose 啟動失敗,請確認 Docker Desktop 有在跑。" -ForegroundColor Red
        exit 1
    }
    $pgOk = Wait-ContainerHealthy -ContainerName "mms-postgres" -TimeoutSec 60
    $redisOk = Wait-ContainerHealthy -ContainerName "mms-redis" -TimeoutSec 60
    if (-not $pgOk -or -not $redisOk) {
        Write-Host "Postgres/Redis 一直沒 healthy,檢查 docker compose logs。" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Postgres + Redis 已 healthy。" -ForegroundColor Green
} else {
    Write-Host "[1/3] 不執行 docker compose，驗證既有容器(-SkipDocker)..." -ForegroundColor Yellow
    $pgOk = (Get-ContainerHealth -ContainerName "mms-postgres") -eq "healthy"
    $redisOk = (Get-ContainerHealth -ContainerName "mms-redis") -eq "healthy"
    if (-not $pgOk -or -not $redisOk) {
        Write-Host "-SkipDocker 要求既有 mms-postgres 與 mms-redis 都為 healthy。" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Postgres + Redis 已 healthy。" -ForegroundColor Green
}

# 2) ngrok --------------------------------------------------------------
$ngrokUrl = $null
$ngrokPid = $null
if (-not $NoNgrok) {
    Write-Host "[2/3] 啟動 ngrok ..." -ForegroundColor Yellow
    $existingNgrokPid = Resolve-ManagedProcessId -TrackedProcessId $null `
        -Port $NgrokApiPort -Kind "Ngrok"
    if ($existingNgrokPid) {
        Write-Host "  ngrok 已經在跑(PID $existingNgrokPid),沿用現有隧道。" -ForegroundColor DarkGray
        $ngrokPid = $existingNgrokPid
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 10
    } else {
        $ngrokExe = Resolve-NgrokExe
        if (-not $ngrokExe) {
            Write-Host "  找不到 ngrok.exe。設定 `$env:NGROK_EXE 指到執行檔,或用 -NoNgrok 跳過。" -ForegroundColor Red
            exit 1
        }
        $proc = Start-Process -FilePath $ngrokExe `
            -ArgumentList "http", "$AppPort", "--log=stdout" `
            -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $LogsDir "ngrok.out.log") `
            -RedirectStandardError (Join-Path $LogsDir "ngrok.err.log")
        $ngrokPid = $proc.Id
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 20
        if (-not $ngrokUrl) {
            Write-Host "  ngrok 起了(PID $ngrokPid)但拿不到公開 URL,看 scripts\.logs\ngrok.err.log。" -ForegroundColor Red
            Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok(啟動失敗)"
            exit 1
        } else {
            Write-Host "  ngrok 就緒(PID $ngrokPid)。" -ForegroundColor Green
        }
    }
} else {
    Write-Host "[2/3] 跳過 ngrok(-NoNgrok)。" -ForegroundColor DarkGray
}

# 3) Spring Boot ----------------------------------------------------------
Write-Host "[3/3] 啟動 Spring Boot ..." -ForegroundColor Yellow
$existingAppPid = Resolve-ManagedProcessId -TrackedProcessId $null `
    -Port $AppPort -Kind "SpringBoot"
if ($existingAppPid) {
    $existingHealthy = Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 10
    if (-not $existingHealthy) {
        Write-Host "  偵測到本專案行程 PID $existingAppPid，但 health check 未通過。" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Spring Boot 已啟動且健康(PID $existingAppPid),不重複啟動。" -ForegroundColor DarkGray
    Write-Host "  要重啟請用 .\dev-restart.ps1" -ForegroundColor DarkGray
    $appPid = $existingAppPid
} else {
    $appLog = Join-Path $LogsDir "spring-boot.out.log"
    $appErrLog = Join-Path $LogsDir "spring-boot.err.log"
    $proc = Start-Process -FilePath "$RepoRoot\mvnw.cmd" `
        -ArgumentList "spring-boot:run", "-Dspring-boot.run.profiles=$Profile" `
        -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $appLog -RedirectStandardError $appErrLog
    $appPid = $proc.Id
    Write-Host "  已送出啟動指令(PID $appPid),等後端 health check ..." -ForegroundColor DarkGray
    $ok = Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 120
    if ($ok) {
        Write-Host "  Spring Boot 就緒。" -ForegroundColor Green
    } else {
        Write-Host "  120 秒內沒看到 health check 過,看 scripts\.logs\spring-boot.err.log 排查。" -ForegroundColor Red
        Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot(啟動失敗)"
        exit 1
    }
}

Write-DevState -Updates @{
    springBootPid = $appPid
    ngrokPid      = $ngrokPid
    ngrokUrl      = $ngrokUrl
    profile       = $Profile
    startedAt     = (Get-Date).ToString("o")
}

Write-Host ""
Write-Host "=== 狀態 ===" -ForegroundColor Cyan
Write-Host "後端:      http://localhost:$AppPort"
if ($ngrokUrl) {
    Write-Host "LINE webhook: $ngrokUrl/api/line/webhook" -ForegroundColor Cyan
    Write-Host "  (網址每次重啟 ngrok 會換,記得回 LINE Developers Console 更新)" -ForegroundColor DarkGray
}
Write-Host "log:        scripts\.logs\*.log"
Write-Host "查狀態:     .\dev-status.ps1"
Write-Host "停止:       .\dev-stop.ps1"

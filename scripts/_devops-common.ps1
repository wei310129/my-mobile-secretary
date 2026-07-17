# dev-{start,stop,restart,status}.ps1 共用:路徑、狀態檔、行程樹操作、健康檢查輪詢。
# 設計原則:狀態(PID/URL)存檔案,因為每次 PowerShell 呼叫都是新 process,變數不會跨呼叫留存;
# 停止一律用 taskkill /T 砍整棵樹(mvnw.cmd 會 fork 出真正的 java.exe,單砍父行程殺不掉它)。

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $PSScriptRoot ".logs"
$StateFile = Join-Path $PSScriptRoot ".dev-state.json"
$AppPort = 8080
$DispatcherPort = 8091
$NgrokApiPort = 4040
$DispatcherRoot = Join-Path $RepoRoot "internal\ai-dispatcher"
$DispatcherPom = Join-Path $DispatcherRoot "pom.xml"
$DispatcherComposeFile = Join-Path $DispatcherRoot "compose.yaml"

function Assert-CommandAvailable {
    param([Parameter(Mandatory)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "找不到必要指令: $Name"
    }
}

function Ensure-LogsDir {
    if (-not (Test-Path $LogsDir)) {
        New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null
    }
}

# Some launchers provide both Path and PATH in the native environment block. Windows PowerShell
# treats environment keys as case-insensitive, so Start-Process rejects that inherited block.
# Rebuild only this script process's PATH entry; system and user environment settings are untouched.
function Normalize-ProcessPathEnvironment {
    $processPath = [System.Environment]::GetEnvironmentVariable("Path", "Process")
    if (-not $processPath) { return }
    [System.Environment]::SetEnvironmentVariable("PATH", $null, "Process")
    [System.Environment]::SetEnvironmentVariable("Path", $processPath, "Process")
}

function Read-DevState {
    if (Test-Path $StateFile) {
        try { return Get-Content $StateFile -Raw | ConvertFrom-Json }
        catch { return [pscustomobject]@{} }
    }
    return [pscustomobject]@{}
}

function Write-DevState {
    param([Parameter(Mandatory)][hashtable]$Updates)
    $state = Read-DevState
    $merged = @{}
    # 先保留舊欄位,PSCustomObject 沒有 Keys,用 Properties 走
    if ($state.PSObject.Properties) {
        foreach ($p in $state.PSObject.Properties) { $merged[$p.Name] = $p.Value }
    }
    foreach ($k in $Updates.Keys) { $merged[$k] = $Updates[$k] }
    $merged | ConvertTo-Json -Depth 5 | Set-Content -Path $StateFile -Encoding utf8
}

# 找監聽某 port 的行程 PID(狀態檔遺失或行程是上一輪殘留時的保底手段)。
function Get-PortOwnerPid {
    param([Parameter(Mandatory)][int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty OwningProcess
    return $conn
}

function Test-ManagedProcess {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][ValidateSet("SpringBoot", "Dispatcher", "Ngrok")][string]$Kind
    )
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    if ($Kind -eq "Ngrok") { return $process.ProcessName -like "ngrok*" }
    try {
        $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" `
                -ErrorAction Stop).CommandLine
        if ($Kind -eq "Dispatcher") {
            return $commandLine -match "internal[\\/]ai-dispatcher[\\/]pom\.xml|AiDispatcherApplication|ai-dispatcher"
        }
        return $commandLine -match "mvnw\.cmd|spring-boot:run|MyMobileSecretaryApplication|my-mobile-secretary"
    } catch {
        return $false
    }
}

function Resolve-ManagedProcessId {
    param(
        [AllowNull()]$TrackedProcessId,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][ValidateSet("SpringBoot", "Dispatcher", "Ngrok")][string]$Kind
    )
    if ($TrackedProcessId -and (Test-ManagedProcess -ProcessId $TrackedProcessId -Kind $Kind)) {
        return [int]$TrackedProcessId
    }
    $portOwner = Get-PortOwnerPid -Port $Port
    if ($portOwner -and (Test-ManagedProcess -ProcessId $portOwner -Kind $Kind)) {
        return [int]$portOwner
    }
    return $null
}

function Assert-PortAvailableOrManaged {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][ValidateSet("SpringBoot", "Dispatcher", "Ngrok")][string]$Kind
    )
    $owner = Get-PortOwnerPid -Port $Port
    if ($owner -and -not (Test-ManagedProcess -ProcessId $owner -Kind $Kind)) {
        throw "Port $Port 已被非本專案的行程 PID $owner 使用，為避免誤殺已停止。"
    }
}

# 砍整棵行程樹(taskkill /T):mvnw.cmd → java.exe 這種父子鏈只砍父行程留下孤兒。
function Stop-ProcessTree {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][string]$Label
    )
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        Write-Host "  $Label`: 行程 $ProcessId 已經不在,跳過。" -ForegroundColor DarkGray
        return
    }
    & taskkill /F /T /PID $ProcessId 2>&1 | Out-Null
    Write-Host "  $Label`: 已停止(PID $ProcessId)。" -ForegroundColor Green
}

# 找 ngrok.exe:優先 $env:NGROK_EXE,其次 PATH,最後回退到使用者曾手動下載的路徑。
function Resolve-NgrokExe {
    if ($env:NGROK_EXE -and (Test-Path $env:NGROK_EXE)) { return $env:NGROK_EXE }
    $onPath = Get-Command ngrok -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $fallback = "$env:USERPROFILE\Downloads\ngrok-v3-stable-windows-amd64\ngrok.exe"
    if (Test-Path $fallback) { return $fallback }
    return $null
}

# 輪詢 HTTP 端點直到 200(或逾時);開機初期連線被拒是正常現象,吞掉錯誤繼續等。
function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Url,
        [int]$TimeoutSec = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ($resp.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

# 輪詢 docker healthcheck 狀態直到 healthy(或逾時)。
function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$ContainerName,
        [int]$TimeoutSec = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = docker inspect -f "{{.State.Health.Status}}" $ContainerName 2>$null
        if ($status -eq "healthy") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-ContainerHealth {
    param([Parameter(Mandatory)][string]$ContainerName)
    $status = docker inspect -f "{{.State.Health.Status}}" $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $status
}

function Test-DockerDaemon {
    docker info --format "{{.ServerVersion}}" 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

# Reads only the single local Dispatcher lane snapshot. This is used to avoid killing a supervised
# Codex child process during an ordinary restart. Failure to inspect returns $null and never claims
# that an execution is safe to interrupt.
function Get-DispatcherLaneSnapshot {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $null }
    if ((Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres") -ne "healthy") {
        return $null
    }
    $value = docker exec mms-ai-dispatcher-postgres `
        psql -U ai_dispatcher -d ai_dispatcher -tA `
        -c "SELECT state || '|' || COALESCE(active_run_id::text, '') FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT';" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    $parts = (($value | Out-String).Trim()) -split '\|', 2
    if ($parts.Count -ne 2) { return $null }
    $state = $parts[0]
    $known = @("IDLE", "WAITING", "STARTING", "RUNNING", "RECOVERING", "PAUSED")
    if (-not ($known -contains $state)) { return $null }
    $activeRunId = $parts[1]
    if ($activeRunId) {
        $parsedRunId = [guid]::Empty
        if (-not [guid]::TryParse($activeRunId, [ref]$parsedRunId)) { return $null }
    }
    return [pscustomobject]@{
        State       = $state
        ActiveRunId = $activeRunId
    }
}

function Get-DispatcherLaneState {
    $snapshot = Get-DispatcherLaneSnapshot
    if ($snapshot) { return $snapshot.State }
    return $null
}

function Test-DispatcherLaneActive {
    param([AllowNull()][string]$State)
    return @("STARTING", "RUNNING", "RECOVERING") -contains $State
}

# Starting the development environment is deliberately disarmed. A future explicit arm command
# may override these values only after the execution adapter and per-run event bound are ready.
function Disable-DispatcherAutomationEnvironment {
    $env:DEVELOPMENT_FEED_ENABLED = "false"
    $env:AI_DISPATCHER_MAIN_FEED_ENABLED = "false"
    $env:AI_DISPATCHER_CODEX_CLI_ENABLED = "false"
    $env:AI_DISPATCHER_ENABLED = "false"
}

# 從 ngrok 本機 API(4040)讀目前的公開 URL;剛啟動時 API 還沒 ready,輪詢幾秒。
function Get-NgrokPublicUrl {
    param([int]$TimeoutSec = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$NgrokApiPort/api/tunnels" -TimeoutSec 2 -ErrorAction Stop
            $https = $resp.tunnels | Where-Object { $_.proto -eq "https" } | Select-Object -First 1
            if ($https) { return $https.public_url }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $null
}

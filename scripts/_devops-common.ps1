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

function Wait-TcpPort {
    param(
        [string]$HostName = "127.0.0.1",
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSec = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync($HostName, $Port)
            if ($connect.Wait(2000) -and $client.Connected) { return $true }
        } catch {
            # The container can be healthy before Docker Desktop publishes its Windows host port.
        } finally {
            $client.Dispose()
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Test-DockerDaemon {
    docker info --format "{{.ServerVersion}}" 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Resolve-DockerDesktopExe {
    $candidates = @(
        (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"),
        (Join-Path $env:LOCALAPPDATA "Docker\Docker Desktop.exe")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    return $null
}

function Wait-DockerDaemon {
    param([int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $nextProgress = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        if (Test-DockerDaemon) { return $true }
        if ((Get-Date) -ge $nextProgress) {
            $remaining = [Math]::Max(0, [int]($deadline - (Get-Date)).TotalSeconds)
            $desktopCount = @(Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue).Count
            $backendCount = @(Get-Process -Name "com.docker.backend" -ErrorAction SilentlyContinue).Count
            Write-Host "  Waiting for Docker daemon ($remaining sec left; desktop=$desktopCount, backend=$backendCount)..." `
                -ForegroundColor DarkGray
            $nextProgress = (Get-Date).AddSeconds(15)
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# A reboot leaves Docker Desktop stopped. Occasionally Docker Desktop itself reports lingering
# frontend/backend processes and never creates docker_engine. Recovery is deliberately limited to
# Docker Desktop's own known process names, and only runs while the daemon is unreachable.
function Ensure-DockerDaemon {
    # WSL may need more than two minutes after a cold boot or VHDX maintenance. The recovery
    # attempt already proved that known Docker backends are alive, so allow a bounded extra minute
    # instead of declaring failure seconds before docker_engine becomes available.
    param([int]$InitialTimeoutSec = 120, [int]$RecoveryTimeoutSec = 180)
    if (Test-DockerDaemon) { return $true }

    $desktopExe = Resolve-DockerDesktopExe
    if (-not $desktopExe) {
        Write-Host "Docker Desktop executable was not found." -ForegroundColor Red
        return $false
    }

    Write-Host "  Docker daemon is stopped; starting Docker Desktop..." -ForegroundColor Yellow
    Start-Process -FilePath $desktopExe -WindowStyle Hidden | Out-Null
    if (Wait-DockerDaemon -TimeoutSec $InitialTimeoutSec) { return $true }

    Write-Host "  Docker daemon is still unavailable; repairing lingering Docker Desktop processes..." `
        -ForegroundColor Yellow
    $dockerCli = Join-Path (Split-Path -Parent $desktopExe) "DockerCli.exe"
    if (Test-Path -LiteralPath $dockerCli -PathType Leaf) {
        $shutdown = Start-Process -FilePath $dockerCli -ArgumentList "-Shutdown" -WindowStyle Hidden -PassThru
        if (-not $shutdown.WaitForExit(15000)) {
            Stop-Process -Id $shutdown.Id -Force -ErrorAction SilentlyContinue
        }
    }

    Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessName -in @("Docker Desktop", "com.docker.backend") } |
        ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
    Start-Process -FilePath $desktopExe -WindowStyle Hidden | Out-Null
    return Wait-DockerDaemon -TimeoutSec $RecoveryTimeoutSec
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

function Get-DispatcherSessionSnapshot {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $null }
    if ((Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres") -ne "healthy") {
        return $null
    }
    $value = docker exec mms-ai-dispatcher-postgres `
        psql -U ai_dispatcher -d ai_dispatcher -tA `
        -c "SELECT status || '|' || COALESCE(external_session_id, '') FROM agent_session WHERE session_key = 'development-main';" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    $parts = (($value | Out-String).Trim()) -split '\|', 2
    if ($parts.Count -ne 2) { return $null }
    return [pscustomobject]@{
        Status            = $parts[0]
        ExternalSessionId = $parts[1]
    }
}

function Assert-DispatcherSessionReady {
    $session = Get-DispatcherSessionSnapshot
    if (-not $session) {
        throw "Dispatcher session state cannot be inspected."
    }
    if ($session.Status -ne "READY" -or [string]::IsNullOrWhiteSpace($session.ExternalSessionId)) {
        throw "Dispatcher session development-main is not READY and bound."
    }
}

function Test-DispatcherLaneActive {
    param([AllowNull()][string]$State)
    return @("STARTING", "RUNNING", "RECOVERING") -contains $State
}

function Disable-DispatcherAutomationEnvironment {
    $env:DEVELOPMENT_FEED_ENABLED = "false"
    $env:AI_DISPATCHER_MAIN_FEED_ENABLED = "false"
    $env:AI_DISPATCHER_CODEX_CLI_ENABLED = "false"
    $env:AI_DISPATCHER_ENABLED = "false"
}

function Enable-DispatcherAutomationEnvironment {
    param([switch]$AllowDirtyWorktree)

    $required = @(
        "DEVELOPMENT_FEED_TOKEN",
        "DEVELOPMENT_FEED_WORKSPACE_ID",
        "DEVELOPMENT_FEED_ACTOR_ID",
        "AI_DISPATCHER_MAIN_FEED_TOKEN"
    )
    foreach ($name in $required) {
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Required arm environment variable is missing: $name"
        }
    }
    if ($env:DEVELOPMENT_FEED_TOKEN -cne $env:AI_DISPATCHER_MAIN_FEED_TOKEN) {
        throw "Main feed and Dispatcher feed tokens must match."
    }
    if ($env:DEVELOPMENT_FEED_TOKEN.Length -lt 32) {
        throw "Development feed token must contain at least 32 characters."
    }
    foreach ($name in @("DEVELOPMENT_FEED_WORKSPACE_ID", "DEVELOPMENT_FEED_ACTOR_ID")) {
        $parsed = [guid]::Empty
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if (-not [guid]::TryParse($value, [ref]$parsed) -or $parsed -eq [guid]::Empty) {
            throw "$name must be a non-zero UUID."
        }
    }

    $codexExecutable = $env:AI_DISPATCHER_CODEX_CLI_EXECUTABLE
    if ([string]::IsNullOrWhiteSpace($codexExecutable)) {
        $codexCommand = Get-Command codex -CommandType Application -ErrorAction SilentlyContinue
        if ($codexCommand) { $codexExecutable = $codexCommand.Source }
    }
    if ([string]::IsNullOrWhiteSpace($codexExecutable) -or
            -not [System.IO.Path]::IsPathRooted($codexExecutable) -or
            -not (Test-Path -LiteralPath $codexExecutable -PathType Leaf)) {
        throw "AI_DISPATCHER_CODEX_CLI_EXECUTABLE must be an absolute Codex executable path."
    }

    $repository = $env:AI_DISPATCHER_CODEX_REPOSITORY
    if ([string]::IsNullOrWhiteSpace($repository)) { $repository = $RepoRoot }
    if (-not [System.IO.Path]::IsPathRooted($repository) -or
            -not (Test-Path -LiteralPath (Join-Path $repository ".git"))) {
        throw "AI_DISPATCHER_CODEX_REPOSITORY must be an absolute Git working tree."
    }
    if (-not $AllowDirtyWorktree) {
        $worktreeChanges = & git -C $repository status --porcelain=v1 --untracked-files=all
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect the Codex worktree." }
        if ($worktreeChanges) {
            throw "Codex worktree is not clean. Commit/stash changes or explicitly use -AllowDirtyWorktree."
        }
    }

    $env:AI_DISPATCHER_CODEX_CLI_EXECUTABLE = [System.IO.Path]::GetFullPath($codexExecutable)
    $env:AI_DISPATCHER_CODEX_REPOSITORY = [System.IO.Path]::GetFullPath($repository)
    $env:DEVELOPMENT_FEED_ENABLED = "true"
    $env:AI_DISPATCHER_MAIN_FEED_ENABLED = "true"
    $env:AI_DISPATCHER_CODEX_CLI_ENABLED = "true"
    $env:AI_DISPATCHER_ENABLED = "true"
    if ([string]::IsNullOrWhiteSpace($env:AI_DISPATCHER_MAX_EVENTS_PER_RUN)) {
        $env:AI_DISPATCHER_MAX_EVENTS_PER_RUN = "20"
    }
    if ([string]::IsNullOrWhiteSpace($env:AI_DISPATCHER_MAX_EVENT_PAYLOAD_BYTES_PER_RUN)) {
        $env:AI_DISPATCHER_MAX_EVENT_PAYLOAD_BYTES_PER_RUN = "65536"
    }
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

function Get-LocalSecretValue {
    param([Parameter(Mandatory)][string]$Name)
    $secretsFile = Join-Path $RepoRoot "secrets.yaml"
    if (-not (Test-Path -LiteralPath $secretsFile -PathType Leaf)) { return $null }
    $raw = Get-Content -LiteralPath $secretsFile -Raw -Encoding UTF8
    $pattern = '(?m)^\s*' + [regex]::Escape($Name) + '\s*:\s*([^#\r\n]+)'
    $match = [regex]::Match($raw, $pattern)
    if (-not $match.Success) { return $null }
    $value = $match.Groups[1].Value.Trim().Trim('"').Trim("'")
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq "not-configured") { return $null }
    return $value
}

function Get-LineAccessToken {
    $configuredToken = Get-LocalSecretValue -Name "channel-access-token"
    if ($configuredToken) { return $configuredToken }

    $channelId = Get-LocalSecretValue -Name "channel-id"
    $channelSecret = Get-LocalSecretValue -Name "channel-secret"
    if (-not $channelId -or -not $channelSecret) {
        throw "LINE channel-id/channel-secret are missing from secrets.yaml."
    }
    $response = Invoke-RestMethod -Method Post `
        -Uri "https://api.line.me/oauth2/v3/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ grant_type = "client_credentials"; client_id = $channelId; client_secret = $channelSecret } `
        -TimeoutSec 15 -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "LINE token endpoint returned no access token."
    }
    return $response.access_token
}

function Get-LineWebhookConfiguration {
    try {
        $token = Get-LineAccessToken
        $headers = @{ Authorization = "Bearer $token" }
        $response = Invoke-RestMethod -Method Get `
            -Uri "https://api.line.me/v2/bot/channel/webhook/endpoint" `
            -Headers $headers -TimeoutSec 15 -ErrorAction Stop
        return [pscustomobject]@{
            Success  = $true
            Endpoint = [string]$response.endpoint
            Active   = [bool]$response.active
            Error    = $null
        }
    } catch {
        return [pscustomobject]@{
            Success  = $false
            Endpoint = $null
            Active   = $false
            Error    = $_.Exception.Message
        }
    }
}

function Wait-NgrokWebhookSuccess {
    param(
        [Parameter(Mandatory)][datetimeoffset]$Since,
        [int]$TimeoutSec = 6
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$NgrokApiPort/api/requests/http?limit=20" `
                -TimeoutSec 2 -ErrorAction Stop
            foreach ($entry in $response.requests) {
                $startedAt = [datetimeoffset]::MinValue
                if (-not [datetimeoffset]::TryParse([string]$entry.start, [ref]$startedAt)) { continue }
                if ($startedAt -lt $Since.AddSeconds(-2)) { continue }
                if ([string]$entry.request.method -ne "POST") { continue }
                if ([string]$entry.request.uri -ne "/api/line/webhook") { continue }
                if ([int]$entry.response.status -eq 200) { return $true }
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

# This is the final, outside-in readiness check: LINE's platform calls its configured public
# webhook. Only when it fails should callers inspect ngrok, Spring Boot, Redis and PostgreSQL.
function Test-LineWebhookEndToEnd {
    $startedAt = [datetimeoffset]::Now
    try {
        $token = Get-LineAccessToken
        $headers = @{ Authorization = "Bearer $token" }
        $response = Invoke-RestMethod -Method Post `
            -Uri "https://api.line.me/v2/bot/channel/webhook/test" `
            -Headers $headers -ContentType "application/json" -Body "{}" `
            -TimeoutSec 15 -ErrorAction Stop
        return [pscustomobject]@{
            Success = [bool]$response.success
            Reason  = [string]$response.reason
            Detail  = [string]$response.detail
            Error   = $null
        }
    } catch {
        $requestError = $_.Exception.Message
        if (Wait-NgrokWebhookSuccess -Since $startedAt -TimeoutSec 6) {
            return [pscustomobject]@{
                Success = $true
                Reason  = "CONFIRMED_BY_NGROK"
                Detail  = "LINE reached /api/line/webhook and Spring Boot returned 200."
                Error   = $null
            }
        }
        return [pscustomobject]@{
            Success = $false
            Reason  = "REQUEST_FAILED"
            Detail  = $null
            Error   = $requestError
        }
    }
}

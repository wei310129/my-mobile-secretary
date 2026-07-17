<#
.SYNOPSIS
  Starts the main development environment and the isolated AI Dispatcher.

.PARAMETER Profile
  Spring profile for the main application. Defaults to local.

.PARAMETER NoNgrok
  Skips ngrok for local-only development.

.PARAMETER SkipDocker
  Does not run compose up. Required containers must already be healthy.

.PARAMETER SkipDispatcher
  Does not start the AI Dispatcher application or its PostgreSQL container.

.PARAMETER ArmDispatcher
  Explicitly enables the read-only issue feed, scheduler, and Codex CLI adapter after preflight.
#>
param(
    [string]$Profile = "local",
    [switch]$NoNgrok,
    [switch]$SkipDocker,
    [switch]$SkipDispatcher,
    [switch]$ArmDispatcher,
    [switch]$AllowDirtyWorktree
)

. "$PSScriptRoot\_devops-common.ps1"
Normalize-ProcessPathEnvironment
if ($SkipDispatcher -and $ArmDispatcher) { throw "-SkipDispatcher cannot be combined with -ArmDispatcher." }
if ($AllowDirtyWorktree -and -not $ArmDispatcher) { throw "-AllowDirtyWorktree requires -ArmDispatcher." }
if ($ArmDispatcher) {
    Enable-DispatcherAutomationEnvironment -AllowDirtyWorktree:$AllowDirtyWorktree
} else {
    Disable-DispatcherAutomationEnvironment
}
Ensure-LogsDir
Set-Location $RepoRoot

try {
    Assert-CommandAvailable -Name "docker"
    if (-not (Test-Path "$RepoRoot\mvnw.cmd")) { throw "Missing Maven wrapper: $RepoRoot\mvnw.cmd" }
    if (-not (Test-DockerDaemon)) { throw "Docker daemon is not running. Start Docker Desktop first." }
    if ($ArmDispatcher) { Assert-DispatcherSessionReady }
    Assert-PortAvailableOrManaged -Port $AppPort -Kind "SpringBoot"
    if (-not $NoNgrok) { Assert-PortAvailableOrManaged -Port $NgrokApiPort -Kind "Ngrok" }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

Write-Host "=== Starting development environment (profile=$Profile) ===" -ForegroundColor Cyan
$automationMode = if ($ArmDispatcher) { "ARMED" } else { "DISARMED" }
$automationColor = if ($ArmDispatcher) { "Yellow" } else { "DarkGray" }
Write-Host "  Dispatcher automation is $automationMode." -ForegroundColor $automationColor

# 1) Main application infrastructure is required.
if (-not $SkipDocker) {
    Write-Host "[1/5] Starting main Postgres and Redis..." -ForegroundColor Yellow
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Main docker compose startup failed." -ForegroundColor Red
        exit 1
    }
    $pgOk = Wait-ContainerHealthy -ContainerName "mms-postgres" -TimeoutSec 60
    $redisOk = Wait-ContainerHealthy -ContainerName "mms-redis" -TimeoutSec 60
    if (-not $pgOk -or -not $redisOk) {
        Write-Host "Main Postgres or Redis did not become healthy." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[1/5] Checking existing main containers (-SkipDocker)..." -ForegroundColor Yellow
    $pgOk = (Get-ContainerHealth -ContainerName "mms-postgres") -eq "healthy"
    $redisOk = (Get-ContainerHealth -ContainerName "mms-redis") -eq "healthy"
    if (-not $pgOk -or -not $redisOk) {
        Write-Host "-SkipDocker requires healthy mms-postgres and mms-redis containers." -ForegroundColor Red
        exit 1
    }
}

Write-Host "  Main Postgres and Redis are healthy." -ForegroundColor Green

# 2) ngrok is part of the main application lifecycle.
$ngrokUrl = $null
$ngrokPid = $null
if (-not $NoNgrok) {
    Write-Host "[2/5] Starting ngrok..." -ForegroundColor Yellow
    $existingNgrokPid = Resolve-ManagedProcessId -TrackedProcessId $null -Port $NgrokApiPort -Kind "Ngrok"
    if ($existingNgrokPid) {
        $ngrokPid = $existingNgrokPid
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 10
        Write-Host "  Reusing ngrok PID $ngrokPid." -ForegroundColor DarkGray
    } else {
        $ngrokExe = Resolve-NgrokExe
        if (-not $ngrokExe) {
            Write-Host "ngrok.exe was not found. Set `$env:NGROK_EXE or use -NoNgrok." -ForegroundColor Red
            exit 1
        }
        $proc = Start-Process -FilePath $ngrokExe `
            -ArgumentList "http", "$AppPort", "--log=stdout", "--log-level=warn" `
            -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $LogsDir "ngrok.out.log") `
            -RedirectStandardError (Join-Path $LogsDir "ngrok.err.log")
        $ngrokPid = $proc.Id
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 20
        if (-not $ngrokUrl) {
            Write-Host "ngrok did not expose a public URL. Check scripts\.logs\ngrok.err.log." -ForegroundColor Red
            Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok (failed startup)"
            exit 1
        }
        Write-Host "  ngrok is ready (PID $ngrokPid)." -ForegroundColor Green
    }
} else {
    Write-Host "[2/5] Skipping ngrok (-NoNgrok)." -ForegroundColor DarkGray
}

# 3) The main application is required and becomes healthy before any Dispatcher work.
Write-Host "[3/5] Starting the main Spring Boot application..." -ForegroundColor Yellow
$existingAppPid = Resolve-ManagedProcessId -TrackedProcessId $null -Port $AppPort -Kind "SpringBoot"
if ($existingAppPid) {
    $previousState = Read-DevState
    if ([bool]$previousState.dispatcherArmed -ne [bool]$ArmDispatcher) {
        throw "Existing main application mode differs from the requested mode; use dev-restart.ps1."
    }
    if (-not (Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 10)) {
        Write-Host "Main PID $existingAppPid exists but its health check failed." -ForegroundColor Red
        exit 1
    }
    $appPid = $existingAppPid
    Write-Host "  Main application is already healthy (PID $appPid)." -ForegroundColor DarkGray
} else {
    $proc = Start-Process -FilePath "$RepoRoot\mvnw.cmd" `
        -ArgumentList "spring-boot:run", "-Dspring-boot.run.profiles=$Profile" `
        -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $LogsDir "spring-boot.out.log") `
        -RedirectStandardError (Join-Path $LogsDir "spring-boot.err.log")
    $appPid = $proc.Id
    if (-not (Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 120)) {
        Write-Host "Main health check failed. Check scripts\.logs\spring-boot.err.log." -ForegroundColor Red
        Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot (failed startup)"
        exit 1
    }
    Write-Host "  Main application is ready (PID $appPid)." -ForegroundColor Green
}

# 4) Dispatcher database failure cannot delay the main application becoming ready.
$dispatcherDbReady = $false
if ($SkipDispatcher) {
    Write-Host "[4/5] Skipping AI Dispatcher database (-SkipDispatcher)." -ForegroundColor DarkGray
} elseif (-not (Test-Path $DispatcherComposeFile) -or -not (Test-Path $DispatcherPom)) {
    Write-Host "[4/5] AI Dispatcher files are incomplete. Main application remains available." -ForegroundColor Yellow
} elseif (-not $SkipDocker) {
    Write-Host "[4/5] Starting the isolated Dispatcher PostgreSQL..." -ForegroundColor Yellow
    docker compose -f $DispatcherComposeFile up -d
    if ($LASTEXITCODE -eq 0) {
        $dispatcherDbReady = Wait-ContainerHealthy -ContainerName "mms-ai-dispatcher-postgres" -TimeoutSec 60
    }
    if ($dispatcherDbReady) {
        Write-Host "  Dispatcher PostgreSQL is healthy (isolated DB and volume)." -ForegroundColor Green
    } else {
        Write-Host "  Dispatcher PostgreSQL failed. Dispatcher is skipped; main application remains up." -ForegroundColor Yellow
    }
} else {
    Write-Host "[4/5] Checking existing Dispatcher PostgreSQL (-SkipDocker)..." -ForegroundColor Yellow
    $dispatcherDbReady = (Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres") -eq "healthy"
    if ($dispatcherDbReady) {
        Write-Host "  Dispatcher PostgreSQL is healthy." -ForegroundColor Green
    } else {
        Write-Host "  Dispatcher DB is not healthy. Dispatcher is skipped; main application remains up." -ForegroundColor Yellow
    }
}

# 5) Dispatcher is best-effort and cannot roll back a successful main startup.
$dispatcherPid = $null
if ($SkipDispatcher) {
    $previousState = Read-DevState
    $dispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $previousState.dispatcherPid `
        -Port $DispatcherPort -Kind "Dispatcher"
    if ($dispatcherPid) {
        Write-Host "[5/5] Preserving existing AI Dispatcher PID $dispatcherPid (-SkipDispatcher)." `
            -ForegroundColor DarkGray
    } else {
        Write-Host "[5/5] AI Dispatcher was not requested." -ForegroundColor DarkGray
    }
} elseif (-not $dispatcherDbReady) {
    Write-Host "[5/5] AI Dispatcher was not started because its DB is unavailable." -ForegroundColor Yellow
} else {
    Write-Host "[5/5] Starting AI Dispatcher..." -ForegroundColor Yellow
    try {
        Assert-PortAvailableOrManaged -Port $DispatcherPort -Kind "Dispatcher"
        $existingDispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $null `
            -Port $DispatcherPort -Kind "Dispatcher"
        if ($existingDispatcherPid) {
            $previousState = Read-DevState
            if ([bool]$previousState.dispatcherArmed -ne [bool]$ArmDispatcher) {
                throw "Existing Dispatcher mode differs from the requested mode; use dev-restart.ps1."
            }
            if (Wait-HttpOk -Url "http://localhost:$DispatcherPort/actuator/health" -TimeoutSec 10) {
                $dispatcherPid = $existingDispatcherPid
                Write-Host "  AI Dispatcher is already healthy (PID $dispatcherPid)." -ForegroundColor DarkGray
            } else {
                Write-Host "  Dispatcher exists but is unhealthy. It was not interrupted; use dev-restart.ps1." -ForegroundColor Yellow
            }
        } else {
            $proc = Start-Process -FilePath "$RepoRoot\mvnw.cmd" `
                -ArgumentList "-f", "internal\ai-dispatcher\pom.xml", "spring-boot:run" `
                -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $LogsDir "ai-dispatcher.out.log") `
                -RedirectStandardError (Join-Path $LogsDir "ai-dispatcher.err.log")
            $dispatcherPid = $proc.Id
            if (Wait-HttpOk -Url "http://localhost:$DispatcherPort/actuator/health" -TimeoutSec 120) {
                Write-Host "  AI Dispatcher is ready (PID $dispatcherPid)." -ForegroundColor Green
            } else {
                Write-Host "  Dispatcher health check failed. It was stopped; the main application remains up." -ForegroundColor Yellow
                Stop-ProcessTree -ProcessId $dispatcherPid -Label "AI Dispatcher (failed startup)"
                $dispatcherPid = $null
            }
        }
    } catch {
        Write-Host "  Dispatcher skipped: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-DevState -Updates @{
    springBootPid = $appPid
    dispatcherPid = $dispatcherPid
    ngrokPid      = $ngrokPid
    ngrokUrl      = $ngrokUrl
    profile       = $Profile
    dispatcherArmed = [bool]$ArmDispatcher
    startedAt     = (Get-Date).ToString("o")
}

Write-Host ""
Write-Host "=== Status ===" -ForegroundColor Cyan
Write-Host "Main:          http://localhost:$AppPort"
if ($dispatcherPid) {
    Write-Host "AI Dispatcher: http://localhost:$DispatcherPort ($automationMode)"
}
if ($ngrokUrl) { Write-Host "LINE webhook:  $ngrokUrl/api/line/webhook" -ForegroundColor Cyan }
Write-Host "Logs:          scripts\.logs\"
Write-Host "Inspect:       .\scripts\dev-status.ps1"
Write-Host "Stop:          .\scripts\dev-stop.ps1"
if ($ArmDispatcher -and -not $dispatcherPid) { exit 2 }

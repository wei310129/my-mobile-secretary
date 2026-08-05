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

.PARAMETER VerboseOutput
  Prints normal per-layer startup progress in addition to the final summary.
#>
param(
    [string]$Profile = "local",
    [switch]$NoNgrok,
    [switch]$SkipDocker,
    [switch]$SkipDispatcher,
    [switch]$ArmDispatcher,
    [switch]$AllowDirtyWorktree,
    [switch]$VerboseOutput
)

. "$PSScriptRoot\_devops-common.ps1"
$script:DevVerboseOutput = [bool]$VerboseOutput
Normalize-ProcessPathEnvironment
if ($SkipDispatcher -and $ArmDispatcher) { throw "-SkipDispatcher cannot be combined with -ArmDispatcher." }
if ($AllowDirtyWorktree -and -not $ArmDispatcher) { throw "-AllowDirtyWorktree requires -ArmDispatcher." }
if ($ArmDispatcher) {
    Enable-DispatcherAutomationEnvironment -AllowDirtyWorktree:$AllowDirtyWorktree
} else {
    Disable-DispatcherAutomationEnvironment
}
Ensure-LogsDir
$script:StartServiceGeneration = $null
function Resolve-StartLogPath {
    param([Parameter(Mandatory)][string]$Name)
    if ($null -eq $script:StartServiceGeneration) { $script:StartServiceGeneration = New-DevServiceGeneration }
    return Join-Path $script:StartServiceGeneration.LogDirectory $Name
}
Set-Location $RepoRoot
$lifecycleLease = Enter-DevLifecycleCoordination -Action start
$lifecycleOutcome = 'FAILED'

try {
    Assert-CommandAvailable -Name "docker"
    if (-not (Test-Path "$RepoRoot\mvnw.cmd")) { throw "Missing Maven wrapper: $RepoRoot\mvnw.cmd" }
    if (-not (Ensure-DockerDaemon)) {
        throw "Docker daemon could not be started. Check Docker Desktop logs, then rerun dev-start.ps1."
    }
    if ($ArmDispatcher) { Assert-DispatcherSessionReady }
    Assert-PortAvailableOrManaged -Port $AppPort -Kind "SpringBoot"
    if (-not $NoNgrok) { Assert-PortAvailableOrManaged -Port $NgrokApiPort -Kind "Ngrok" }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action start -Outcome $lifecycleOutcome
    exit 1
}

try {
Write-DevProgress -Message "=== Starting development environment (profile=$Profile) ===" -ForegroundColor Cyan
$automationMode = if ($ArmDispatcher) { "ARMED" } else { "DISARMED" }
$automationColor = if ($ArmDispatcher) { "Yellow" } else { "DarkGray" }
Write-DevProgress -Message "  Dispatcher automation is $automationMode." -ForegroundColor $automationColor

# 1) Main application infrastructure is required.
if (-not $SkipDocker) {
    Write-DevProgress -Message "[1/6] Starting main Postgres and Redis..." -ForegroundColor Yellow
    $composeOutput = docker compose up -d 2>&1
    if ($LASTEXITCODE -ne 0) {
        $composeOutput | ForEach-Object { Write-Host $_ }
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
    Write-DevProgress -Message "[1/6] Checking existing main containers (-SkipDocker)..." -ForegroundColor Yellow
    $pgOk = (Get-ContainerHealth -ContainerName "mms-postgres") -eq "healthy"
    $redisOk = (Get-ContainerHealth -ContainerName "mms-redis") -eq "healthy"
    if (-not $pgOk -or -not $redisOk) {
        Write-Host "-SkipDocker requires healthy mms-postgres and mms-redis containers." -ForegroundColor Red
        exit 1
    }
}

if (-not (Wait-TcpPort -Port 5432 -TimeoutSec 60) -or
        -not (Wait-TcpPort -Port 6379 -TimeoutSec 60)) {
    Write-Host "Containers are healthy, but PostgreSQL or Redis is not reachable through its Windows host port." `
        -ForegroundColor Red
    Write-Host "Docker Desktop port forwarding is not ready; rerun dev-start.ps1 after Docker engine recovery." `
        -ForegroundColor Yellow
    exit 1
}

Write-DevProgress -Message "  Main Postgres and Redis are healthy." -ForegroundColor Green

# 2) ngrok is part of the main application lifecycle.
$ngrokUrl = $null
$ngrokPid = $null
$ngrokOwnershipReceiptId = $null
$lineWebhookConfiguration = $null
if (-not $NoNgrok) {
    Write-DevProgress -Message "[2/6] Starting ngrok for the configured LINE webhook..." -ForegroundColor Yellow
    $lineWebhookConfiguration = Get-LineWebhookConfiguration
    if (-not $lineWebhookConfiguration.Success) {
        Write-Host "Could not read LINE's configured webhook endpoint: $($lineWebhookConfiguration.Error)" `
            -ForegroundColor Red
        exit 1
    }
    if (-not $lineWebhookConfiguration.Active) {
        Write-Host "LINE webhook is disabled in LINE Developers." -ForegroundColor Red
        exit 1
    }
    try {
        $lineWebhookUri = [uri]$lineWebhookConfiguration.Endpoint
    } catch {
        Write-Host "LINE returned an invalid webhook endpoint." -ForegroundColor Red
        exit 1
    }
    if (-not $lineWebhookUri.IsAbsoluteUri -or $lineWebhookUri.Scheme -ne "https" -or
            [string]::IsNullOrWhiteSpace($lineWebhookUri.Host)) {
        Write-Host "LINE webhook endpoint must be an absolute HTTPS URL." -ForegroundColor Red
        exit 1
    }
    if ($lineWebhookUri.AbsolutePath -ne "/api/line/webhook") {
        Write-Host "LINE webhook path is '$($lineWebhookUri.AbsolutePath)', expected '/api/line/webhook'." `
            -ForegroundColor Red
        exit 1
    }

    $existingNgrokPid = Resolve-ManagedProcessId -TrackedProcessId $null -Port $NgrokApiPort -Kind "Ngrok"
    if ($existingNgrokPid) {
        $ngrokPid = $existingNgrokPid
        $previousNgrokState = Read-DevState
        if ($previousNgrokState.ngrokPid -eq $ngrokPid -and
                $previousNgrokState.PSObject.Properties['ngrokOwnershipReceiptId']) {
            $ngrokOwnershipReceiptId = $previousNgrokState.ngrokOwnershipReceiptId
        }
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 10
        $ngrokHost = if ($ngrokUrl) { ([uri]$ngrokUrl).Host } else { $null }
        if ($ngrokHost -eq $lineWebhookUri.Host) {
            Write-DevProgress -Message "  Reusing ngrok PID $ngrokPid for $ngrokHost." -ForegroundColor DarkGray
        } else {
            Write-Host "  Existing ngrok host does not match LINE; restarting the managed tunnel." `
                -ForegroundColor Yellow
            Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok (wrong public host)"
            $ngrokPid = $null
            $ngrokUrl = $null
        }
    }
    if (-not $ngrokPid) {
        $ngrokExe = Resolve-NgrokExe
        if (-not $ngrokExe) {
            Write-Host "ngrok.exe was not found. Set `$env:NGROK_EXE or use -NoNgrok." -ForegroundColor Red
            exit 1
        }
        $ngrokArguments = @(
            "http",
            "--url=$($lineWebhookUri.Host)",
            "$AppPort",
            "--log=stdout",
            "--log-level=warn"
        )
        $proc = Start-Process -FilePath $ngrokExe `
            -ArgumentList $ngrokArguments `
            -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Resolve-StartLogPath "ngrok.out.log") `
            -RedirectStandardError (Resolve-StartLogPath "ngrok.err.log")
        $ngrokPid = $proc.Id
        $ngrokOwnershipReceiptId = New-ManagedProcessOwnershipReceipt `
            -Worktree $RepoRoot -Component Ngrok -ProcessId $ngrokPid
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 20
        if (-not $ngrokUrl) {
            Write-Host "ngrok did not expose a public URL. Check scripts\.logs\ngrok.err.log." -ForegroundColor Red
            Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok (failed startup)"
            exit 1
        }
        if (([uri]$ngrokUrl).Host -ne $lineWebhookUri.Host) {
            Write-Host "ngrok exposed the wrong host: $ngrokUrl" -ForegroundColor Red
            Stop-ProcessTree -ProcessId $ngrokPid -Label "ngrok (wrong public host)"
            exit 1
        }
        Write-DevProgress -Message "  ngrok is ready (PID $ngrokPid)." -ForegroundColor Green
    }
} else {
    Write-DevProgress -Message "[2/6] Skipping ngrok and LINE webhook verification (-NoNgrok)." -ForegroundColor DarkGray
}

# 3) The main application is required and becomes healthy before any Dispatcher work.
Write-DevProgress -Message "[3/6] Starting the main Spring Boot application..." -ForegroundColor Yellow
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
    $runningVersion = Get-RunningServiceVersion
    $checkoutVersion = Get-CheckoutServiceVersion
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    if ($versionComparison.Status -ne "CURRENT") {
        throw "Existing main application version is $($versionComparison.Status) ($($runningVersion.VersionLabel)); use dev-restart.ps1 -SkipDispatcher. $($versionComparison.Reason)"
    }
    $appPid = $existingAppPid
    Write-DevProgress -Message "  Main application is already healthy and current (PID $appPid, version $($runningVersion.VersionLabel))." -ForegroundColor DarkGray
} else {
    $launchFile = if (Test-CoordinationMavenEnabled) { Join-Path $PSScriptRoot 'coordinated-maven-run.ps1' } else { "$RepoRoot\mvnw.cmd" }
    $launchArguments = if (Test-CoordinationMavenEnabled) {
        @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launchFile, '-Application', 'root', '-Profile', $Profile)
    } else {
        @('spring-boot:run', '-Dmaven.test.skip=true', "-Dspring-boot.run.profiles=$Profile")
    }
    $launchExecutable = if (Test-CoordinationMavenEnabled) { Join-Path $PSHOME 'powershell.exe' } else { $launchFile }
    $proc = Start-Process -FilePath $launchExecutable `
        -ArgumentList $launchArguments `
        -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Resolve-StartLogPath "spring-boot.out.log") `
        -RedirectStandardError (Resolve-StartLogPath "spring-boot.err.log")
    $appPid = $proc.Id
    if (-not (Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 240 -ProcessId $appPid)) {
        Write-Host "Main health check failed. Check scripts\.logs\spring-boot.err.log." -ForegroundColor Red
        Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot (failed startup)"
        exit 1
    }
    $runningVersion = Get-RunningServiceVersion
    $checkoutVersion = Get-CheckoutServiceVersion
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    if ($versionComparison.Status -notin @("CURRENT", "DIRTY")) {
        Write-Host "Main application started but version verification is $($versionComparison.Status)." -ForegroundColor Red
        Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot (unverifiable version)" -Port $AppPort
        exit 1
    }
    $versionColor = if ($versionComparison.Status -eq "CURRENT") { "Green" } else { "Yellow" }
    Write-DevProgress -Message "  Main application is ready (PID $appPid, version $($runningVersion.VersionLabel), source=$($versionComparison.Status))." -ForegroundColor $versionColor
}

# 4) Dispatcher database failure cannot delay the main application becoming ready.
$dispatcherDbReady = $false
if ($SkipDispatcher) {
    Write-DevProgress -Message "[4/6] Skipping AI Dispatcher database (-SkipDispatcher)." -ForegroundColor DarkGray
} elseif (-not (Test-Path $DispatcherComposeFile) -or -not (Test-Path $DispatcherPom)) {
    Write-Host "[4/6] AI Dispatcher files are incomplete. Main application remains available." -ForegroundColor Yellow
} elseif (-not $SkipDocker) {
    Write-DevProgress -Message "[4/6] Starting the isolated Dispatcher PostgreSQL..." -ForegroundColor Yellow
    $dispatcherComposeOutput = docker compose -f $DispatcherComposeFile up -d 2>&1
    if ($LASTEXITCODE -eq 0) {
        $dispatcherDbReady = Wait-ContainerHealthy -ContainerName "mms-ai-dispatcher-postgres" -TimeoutSec 60
    }
    if ($dispatcherDbReady) {
        Write-DevProgress -Message "  Dispatcher PostgreSQL is healthy (isolated DB and volume)." -ForegroundColor Green
    } else {
        $dispatcherComposeOutput | ForEach-Object { Write-Host $_ }
        Write-Host "  Dispatcher PostgreSQL failed. Dispatcher is skipped; main application remains up." -ForegroundColor Yellow
    }
} else {
    Write-DevProgress -Message "[4/6] Checking existing Dispatcher PostgreSQL (-SkipDocker)..." -ForegroundColor Yellow
    $dispatcherDbReady = (Get-ContainerHealth -ContainerName "mms-ai-dispatcher-postgres") -eq "healthy"
    if ($dispatcherDbReady) {
        Write-DevProgress -Message "  Dispatcher PostgreSQL is healthy." -ForegroundColor Green
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
        Write-DevProgress -Message "[5/6] Preserving existing AI Dispatcher PID $dispatcherPid (-SkipDispatcher)." `
            -ForegroundColor DarkGray
    } else {
        Write-DevProgress -Message "[5/6] AI Dispatcher was not requested." -ForegroundColor DarkGray
    }
} elseif (-not $dispatcherDbReady) {
    Write-Host "[5/6] AI Dispatcher was not started because its DB is unavailable." -ForegroundColor Yellow
} else {
    Write-DevProgress -Message "[5/6] Starting AI Dispatcher..." -ForegroundColor Yellow
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
                Write-DevProgress -Message "  AI Dispatcher is already healthy (PID $dispatcherPid)." -ForegroundColor DarkGray
            } else {
                Write-Host "  Dispatcher exists but is unhealthy. It was not interrupted; use dev-restart.ps1." -ForegroundColor Yellow
            }
        } else {
            $launchFile = if (Test-CoordinationMavenEnabled) { Join-Path $PSScriptRoot 'coordinated-maven-run.ps1' } else { "$RepoRoot\mvnw.cmd" }
            $launchArguments = if (Test-CoordinationMavenEnabled) {
                @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launchFile, '-Application', 'dispatcher')
            } else {
                @('-f', 'internal\ai-dispatcher\pom.xml', 'spring-boot:run')
            }
            $launchExecutable = if (Test-CoordinationMavenEnabled) { Join-Path $PSHOME 'powershell.exe' } else { $launchFile }
            $proc = Start-Process -FilePath $launchExecutable `
                -ArgumentList $launchArguments `
                -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Resolve-StartLogPath "ai-dispatcher.out.log") `
                -RedirectStandardError (Resolve-StartLogPath "ai-dispatcher.err.log")
            $dispatcherPid = $proc.Id
            if (Wait-HttpOk -Url "http://localhost:$DispatcherPort/actuator/health" -TimeoutSec 120 -ProcessId $dispatcherPid) {
                Write-DevProgress -Message "  AI Dispatcher is ready (PID $dispatcherPid)." -ForegroundColor Green
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

$stateUpdates = @{
    springBootPid = $appPid
    dispatcherPid = $dispatcherPid
    ngrokPid      = $ngrokPid
    ngrokOwnershipReceiptId = $ngrokOwnershipReceiptId
    ngrokUrl      = $ngrokUrl
    profile       = $Profile
    dispatcherArmed = [bool]$ArmDispatcher
    startedAt     = (Get-Date).ToString("o")
    serviceVersion = $runningVersion.VersionLabel
    serviceGitSha = $runningVersion.GitSha
    serviceSourceStatus = $versionComparison.Status
}
if ($script:StartServiceGeneration) { $stateUpdates["serviceGeneration"] = $script:StartServiceGeneration.Generation; $stateUpdates["serviceLogDirectory"] = $script:StartServiceGeneration.LogDirectory }
Write-DevState -Updates $stateUpdates

# Final verification is intentionally outside-in. If it passes, every required layer from LINE's
# platform through ngrok and Spring Boot is connected. Layered diagnostics are only needed on failure.
$lineWebhookReady = $true
if (-not $NoNgrok) {
    Write-DevProgress -Message "[6/6] Testing LINE -> ngrok -> Spring Boot end to end..." -ForegroundColor Yellow
    $lineTest = Test-LineWebhookEndToEnd
    $lineWebhookReady = $lineTest.Success
    if ($lineWebhookReady) {
        Write-DevProgress -Message "  LINE webhook end-to-end test passed." -ForegroundColor Green
    } else {
        Write-Host "  LINE webhook end-to-end test failed." -ForegroundColor Red
        if ($lineTest.Reason) { Write-Host "  LINE reason: $($lineTest.Reason)" -ForegroundColor Yellow }
        if ($lineTest.Detail) { Write-Host "  LINE detail: $($lineTest.Detail)" -ForegroundColor Yellow }
        if ($lineTest.Error) { Write-Host "  Request error: $($lineTest.Error)" -ForegroundColor Yellow }
        Write-Host "  Layer check: LINE endpoint active=$($lineWebhookConfiguration.Active), host=$($lineWebhookUri.Host)"
        Write-Host "  Layer check: ngrok=$ngrokUrl"
        $localHealth = try {
            (Invoke-RestMethod -Uri "http://localhost:$AppPort/actuator/health" -TimeoutSec 3).status
        } catch { "no response" }
        Write-Host "  Layer check: Spring Boot health=$localHealth"
        Write-Host "  Inspect logs under scripts\.logs\." -ForegroundColor Yellow
    }
} else {
    Write-DevProgress -Message "[6/6] LINE webhook test skipped (-NoNgrok)." -ForegroundColor DarkGray
}

if (-not $lineWebhookReady) { exit 1 }
if ($ArmDispatcher -and -not $dispatcherPid) { exit 2 }
$dispatcherSummary = if ($SkipDispatcher) { "dispatcher=skipped" } elseif ($dispatcherPid) { "dispatcher=$automationMode" } else { "dispatcher=unavailable" }
$lineSummary = if ($NoNgrok) { "LINE=skipped" } else { "LINE=connected" }
Write-Host "Development environment ready: main=http://localhost:$AppPort; version=$($runningVersion.VersionLabel); source=$($versionComparison.Status); $dispatcherSummary; $lineSummary; logs=scripts\.logs\." -ForegroundColor Green
$lifecycleOutcome = 'READY'
} finally {
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action start -Outcome $lifecycleOutcome
}

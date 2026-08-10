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
Initialize-LocalApplicationSecretEnvironment
$script:StartServiceGeneration = $null
$script:StartServiceGenerationPublished = $false
$script:StartNewNgrokPid = $null
$script:StartNewAppPid = $null
$script:StartNewDispatcherPid = $null
$script:StartPreviousState = Read-DevState
$script:StartStateWritten = $false
function Resolve-StartLogPath {
    param([Parameter(Mandatory)][string]$Name)
    if ($null -eq $script:StartServiceGeneration) { $script:StartServiceGeneration = New-DevServiceGeneration }
    return Join-Path $script:StartServiceGeneration.LogDirectory $Name
}
Set-Location $RepoRoot
$lifecycleLease = Enter-DevLifecycleCoordination -Action start
$lifecycleOutcome = 'FAILED'

$devStartFailure=$null
try {
    if (-not (Test-Path "$RepoRoot\mvnw.cmd")) { throw "Missing Maven wrapper: $RepoRoot\mvnw.cmd" }
    $sharedInfrastructureResult = if ($SkipDocker) {
        Get-SharedInfrastructureStatus -Name @('main-postgres','main-redis')
    } else {
        Ensure-SharedInfrastructure -Name @('main-postgres','main-redis')
    }
    if ($sharedInfrastructureResult.Outcome -ne 'READY') {
        throw "Shared development infrastructure is $($sharedInfrastructureResult.Classification): $($sharedInfrastructureResult.Reason)"
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

# 1) Main application infrastructure is a fixed shared-persistent contract.
$sharedAction = if ($sharedInfrastructureResult.PSObject.Properties['Action']) { $sharedInfrastructureResult.Action } else { 'VERIFIED' }
Write-DevProgress -Message "[1/6] Shared Postgres and Redis: $sharedAction (automatic worktree reuse)." -ForegroundColor Yellow

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
$springBootOwnershipReceiptId = $null
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

    $previousNgrokState = Read-DevState
    $trackedNgrokPid = if ($previousNgrokState.PSObject.Properties['ngrokPid']) { $previousNgrokState.ngrokPid } else { $null }
    $existingNgrokPid = Resolve-ManagedProcessId -TrackedProcessId $trackedNgrokPid -Port $NgrokApiPort -Kind "Ngrok"
    if ($existingNgrokPid) {
        $ngrokPid = $existingNgrokPid
        if ($previousNgrokState.ngrokPid -eq $ngrokPid -and
                $previousNgrokState.PSObject.Properties['ngrokOwnershipReceiptId']) {
            $ngrokOwnershipReceiptId = $previousNgrokState.ngrokOwnershipReceiptId
        }
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 10
        $ngrokHost = if ($ngrokUrl) { ([uri]$ngrokUrl).Host } else { $null }
        if ($ngrokHost -eq $lineWebhookUri.Host) {
            Write-DevProgress -Message "  Reusing ngrok PID $ngrokPid for $ngrokHost." -ForegroundColor DarkGray
        } else {
            throw 'Existing managed ngrok tunnel does not match the configured LINE endpoint; use dev-restart.ps1.'
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
            "http://localhost:$AppPort",
            "--log=stdout",
            "--log-level=warn"
        )
        if ($null -eq $script:StartServiceGeneration) { $script:StartServiceGeneration = New-DevServiceGeneration }
        $ngrokOwnership = Start-ManagedDurableProcess `
            -Worktree $RepoRoot -Component Ngrok -ExecutablePath $ngrokExe -Arguments $ngrokArguments `
            -Generation $script:StartServiceGeneration.Generation -ExpectedPort $AppPort `
            -StandardOutputPath (Resolve-StartLogPath "ngrok.out.log") `
            -StandardErrorPath (Resolve-StartLogPath "ngrok.err.log") `
            -FailureStopAdapter { param($id) Stop-ProcessTree -ProcessId $id -Label 'ngrok (startup rollback)' -Port $NgrokApiPort } `
            -ReadinessProbe {
                param($phase)
                $timeout = if ($phase -eq 'BEFORE_PUBLICATION') { 20 } else { 5 }
                $candidate = Get-NgrokPublicUrl -TimeoutSec $timeout
                $ready = $false
                if ($candidate) {
                    try { $ready = ([uri]$candidate).Host -eq $lineWebhookUri.Host } catch { $ready = $false }
                }
                [pscustomobject]@{Ready=$ready;Code=if($ready){'TUNNEL_CONTRACT_MATCH'}else{'TUNNEL_CONTRACT_NOT_READY'}}
            }
        $ngrokPid = $ngrokOwnership.ProcessId
        $script:StartNewNgrokPid = $ngrokPid
        $ngrokOwnershipReceiptId = $ngrokOwnership.ReceiptId
        $ngrokUrl = Get-NgrokPublicUrl -TimeoutSec 5
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
$previousState = Read-DevState
$trackedAppPid = if ($previousState.PSObject.Properties['springBootPid']) { $previousState.springBootPid } else { $null }
$existingAppPid = Resolve-ManagedProcessId -TrackedProcessId $trackedAppPid -Port $AppPort -Kind "SpringBoot"
if ($existingAppPid) {
    if ([bool]$previousState.dispatcherArmed -ne [bool]$ArmDispatcher) {
        throw "Existing main application mode differs from the requested mode; use dev-restart.ps1."
    }
    if (-not (Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 10)) {
        Write-Host "Main PID $existingAppPid exists but its health check failed." -ForegroundColor Red
        exit 1
    }
    $runningVersion = Get-RunningServiceVersion
    $checkoutVersion = Get-CheckoutServiceVersion
    $runningVersion = Set-ServiceVersionContentIdentity -Version $runningVersion -Identity (Get-DevStateValue -State $previousState -Name 'runtimeContentIdentity')
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    if ($versionComparison.Status -notin @("CURRENT","VERIFIED_DIRTY")) {
        throw "Existing main application version is $($versionComparison.Status) ($($runningVersion.VersionLabel)); use dev-restart.ps1 -SkipDispatcher. $($versionComparison.Reason)"
    }
    $runtimeContentIdentityForState=Get-DevStateValue -State $previousState -Name 'runtimeContentIdentity'
    if(-not (Test-RuntimeContentGeneration -Identity $runtimeContentIdentityForState -ServiceGeneration ([string](Get-DevStateValue -State $previousState -Name 'serviceGeneration')))){
        throw 'Existing main application runtime content receipt belongs to another service generation; use dev-restart.ps1 -SkipDispatcher.'
    }
    $appPid = $existingAppPid
    $existingProof = Assert-ManagedRuntimeReceiptCurrent -Worktree $RepoRoot -Component SpringBoot `
        -State $previousState -ReadinessProbe {
            [pscustomobject]@{Ready=(Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 5 -ProcessId $appPid);Code='ACTUATOR_REUSE_RECHECK'}
        }
    $springBootOwnershipReceiptId = $existingProof.ReceiptId
    Write-DevProgress -Message "  Main application is already healthy and current (PID $appPid, version $($runningVersion.VersionLabel))." -ForegroundColor DarkGray
} else {
    $buildContentIdentity = Get-ProductionContentIdentity -RepoRoot $RepoRoot
    $launchFile = if (Test-CoordinationMavenEnabled) { Join-Path $PSScriptRoot 'coordinated-maven-run.ps1' } else { "$RepoRoot\mvnw.cmd" }
    $launchArguments = if (Test-CoordinationMavenEnabled) {
        @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launchFile, '-Application', 'root', '-Profile', $Profile)
    } else {
        @('spring-boot:run', '-Dmaven.test.skip=true', "-Dspring-boot.run.profiles=$Profile")
    }
    $launchExecutable = if (Test-CoordinationMavenEnabled) { Join-Path $PSHOME 'powershell.exe' } else { $launchFile }
    if ($null -eq $script:StartServiceGeneration) { $script:StartServiceGeneration = New-DevServiceGeneration }
    $appOwnership = Start-ManagedDurableProcess -Worktree $RepoRoot -Component SpringBoot `
        -ExecutablePath $launchExecutable -Arguments $launchArguments `
        -Generation $script:StartServiceGeneration.Generation -ExpectedPort $AppPort `
        -StandardOutputPath (Resolve-StartLogPath "spring-boot.out.log") `
        -StandardErrorPath (Resolve-StartLogPath "spring-boot.err.log") `
        -FailureStopAdapter { param($id) Stop-ProcessTree -ProcessId $id -Label 'Spring Boot (startup rollback)' -Port $AppPort } `
        -ReadinessProbe {
            param($phase,$managedPid)
            $timeout = if ($phase -eq 'BEFORE_PUBLICATION') { 240 } else { 5 }
            $ready = Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec $timeout -ProcessId $managedPid
            [pscustomobject]@{Ready=$ready;Code=if($ready){'ACTUATOR_UP'}else{'ACTUATOR_NOT_READY'}}
        }
    $appPid = $appOwnership.ProcessId
    $script:StartNewAppPid = $appPid
    $springBootOwnershipReceiptId = $appOwnership.ReceiptId
    $runningVersion = Get-RunningServiceVersion
    $checkoutVersion = Get-CheckoutServiceVersion
    $runningVersion = Set-ServiceVersionContentIdentity -Version $runningVersion -Identity $buildContentIdentity
    $versionComparison = Compare-ServiceVersion -Running $runningVersion -Checkout $checkoutVersion
    if ($versionComparison.Status -notin @("CURRENT", "VERIFIED_DIRTY")) {
        Write-Host "Main application started but version verification is $($versionComparison.Status)." -ForegroundColor Red
        Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot (unverifiable version)" -Port $AppPort
        exit 1
    }
    $versionColor = if ($versionComparison.Status -eq "CURRENT") { "Green" } else { "Yellow" }
    $runtimeContentIdentityForState=[ordered]@{
        schemaVersion=1; fingerprint=$checkoutVersion.ProductionContentFingerprint
        head=$checkoutVersion.GitSha; worktreeId=$checkoutVersion.WorktreeId; repoId=$checkoutVersion.RepoId
        gitDirectoryId=$buildContentIdentity.GitDirectoryId; observedAt=$buildContentIdentity.ObservedAt
        serviceGeneration=if($script:StartServiceGeneration){$script:StartServiceGeneration.Generation}else{$null}
    }
    Write-DevProgress -Message "  Main application is ready (PID $appPid, version $($runningVersion.VersionLabel), source=$($versionComparison.Status))." -ForegroundColor $versionColor
}

# 4) Dispatcher database failure cannot delay the main application becoming ready.
$dispatcherDbReady = $false
if ($SkipDispatcher) {
    Write-DevProgress -Message "[4/6] Skipping AI Dispatcher database (-SkipDispatcher)." -ForegroundColor DarkGray
} elseif (-not (Test-Path $DispatcherComposeFile) -or -not (Test-Path $DispatcherPom)) {
    Write-Host "[4/6] AI Dispatcher files are incomplete. Main application remains available." -ForegroundColor Yellow
} else {
    Write-DevProgress -Message "[4/6] Reusing the isolated Dispatcher PostgreSQL shared contract..." -ForegroundColor Yellow
    $dispatcherInfrastructureResult = if ($SkipDocker) {
        Get-SharedInfrastructureStatus -Name @('dispatcher-postgres')
    } else {
        Ensure-SharedInfrastructure -Name @('dispatcher-postgres')
    }
    if ($dispatcherInfrastructureResult.Outcome -eq 'READY') {
        $dispatcherDbReady = $true
        Write-DevProgress -Message "  Dispatcher PostgreSQL is healthy and identity-verified." -ForegroundColor Green
    } else {
        Write-Host "  Dispatcher DB is $($dispatcherInfrastructureResult.Classification). Dispatcher is skipped; main application remains up." -ForegroundColor Yellow
    }
}

# 5) Dispatcher is best-effort and cannot roll back a successful main startup.
$dispatcherPid = $null
if ($SkipDispatcher) {
    $previousState = Read-DevState
    $previousDispatcherPid = $null
    if ($previousState) {
        $dispatcherProperty = $previousState.PSObject.Properties['dispatcherPid']
        if ($dispatcherProperty) {
            $previousDispatcherPid = $dispatcherProperty.Value
        }
    }
    $dispatcherPid = Resolve-ManagedProcessId -TrackedProcessId $previousDispatcherPid `
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
            $script:StartNewDispatcherPid = $dispatcherPid
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

$stateUpdates = @{
    springBootPid = $appPid
    springBootOwnershipReceiptId = $springBootOwnershipReceiptId
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
    runtimeContentIdentity = $runtimeContentIdentityForState
}
if ($script:StartServiceGeneration) { $stateUpdates["serviceGeneration"] = $script:StartServiceGeneration.Generation; $stateUpdates["serviceLogDirectory"] = $script:StartServiceGeneration.LogDirectory }
Write-DevState -Updates $stateUpdates
$script:StartStateWritten = $true
$durableState=Read-DevState
Assert-ManagedRuntimeReceiptCurrent -Worktree $RepoRoot -Component SpringBoot -State $durableState `
    -ReadinessProbe {
        [pscustomobject]@{Ready=(Wait-HttpOk -Url "http://localhost:$AppPort/actuator/health" -TimeoutSec 5 -ProcessId $appPid);Code='ACTUATOR_POST_STATE_RECHECK'}
    } | Out-Null
if (-not $NoNgrok) {
    Assert-ManagedRuntimeReceiptCurrent -Worktree $RepoRoot -Component Ngrok -State $durableState `
        -ReadinessProbe {
            $candidate = Get-NgrokPublicUrl -TimeoutSec 5
            $ready = $false
            if ($candidate) { try { $ready = ([uri]$candidate).Host -eq $lineWebhookUri.Host } catch { $ready = $false } }
            [pscustomobject]@{Ready=$ready;Code='TUNNEL_POST_STATE_RECHECK'}
        } | Out-Null
}
Publish-DevStartManagedOperationReceipts -State $durableState -SharedInfrastructureResult $sharedInfrastructureResult `
    -LineReady:(-not $NoNgrok)|Out-Null
$script:StartServiceGenerationPublished = $true

$dispatcherSummary = if ($SkipDispatcher) { "dispatcher=skipped" } elseif ($dispatcherPid) { "dispatcher=$automationMode" } else { "dispatcher=unavailable" }
$lineSummary = if ($NoNgrok) { "LINE=skipped" } else { "LINE=connected" }
Write-Host "Development environment ready: main=http://localhost:$AppPort; version=$($runningVersion.VersionLabel); source=$($versionComparison.Status); $dispatcherSummary; $lineSummary; logs=scripts\.logs\." -ForegroundColor Green
$lifecycleOutcome = 'READY'
} catch {
    $devStartFailure=$_
    $lifecycleOutcome='FAILED'
    Write-Host ("Development environment start failed: {0}" -f $_.Exception.Message) -ForegroundColor Red
} finally {
    if (-not $script:StartServiceGenerationPublished) {
        foreach ($startedProcess in @(
            [pscustomobject]@{Pid=$script:StartNewDispatcherPid;Label='AI Dispatcher (startup rollback)';Port=$DispatcherPort},
            [pscustomobject]@{Pid=$script:StartNewAppPid;Label='Spring Boot (startup rollback)';Port=$AppPort},
            [pscustomobject]@{Pid=$script:StartNewNgrokPid;Label='ngrok (startup rollback)';Port=$NgrokApiPort}
        )) {
            if ($startedProcess.Pid) {
                try { Stop-ProcessTree -ProcessId ([int]$startedProcess.Pid) -Label $startedProcess.Label -Port $startedProcess.Port | Out-Null } catch { }
            }
        }
        if ($script:StartStateWritten -and $script:StartServiceGeneration) {
            try {
                Restore-DevStateAfterFailedStart -PreviousState $script:StartPreviousState `
                    -ExpectedGeneration $script:StartServiceGeneration.Generation
            } catch {
                if (-not $devStartFailure) { $devStartFailure = $_ }
                Write-Host 'Failed-start state reconciliation did not complete; ownership remains fail-closed.' -ForegroundColor Red
            }
        }
        Remove-DevServiceGenerationIfUnpublished -Generation $script:StartServiceGeneration
    }
    Exit-DevLifecycleCoordination -Operation $lifecycleLease -Action start -Outcome $lifecycleOutcome
}
if($devStartFailure){exit 1}

<#
.SYNOPSIS
  重啟開發環境。預設只重啟 Spring Boot(改了程式碼後最常見的動作),
  Docker/ngrok 若沒在跑會順便補起來,已經在跑的不動它。

.PARAMETER Full
  全部停掉再全部重開(含 Docker/ngrok),用於環境卡死想整個重來時。

.PARAMETER Profile
  Spring profile,預設 local。

.EXAMPLE
  .\dev-restart.ps1          # 只重啟 Spring Boot
  .\dev-restart.ps1 -Full    # 全部重來
#>
param(
    [switch]$Full,
    [string]$Profile = "local"
)

. "$PSScriptRoot\_devops-common.ps1"
Set-Location $RepoRoot

if ($Full) {
    Write-Host "=== 全部重啟 ===" -ForegroundColor Cyan
    & "$PSScriptRoot\dev-stop.ps1" -Docker
    & "$PSScriptRoot\dev-start.ps1" -Profile $Profile
    return
}

Write-Host "=== 重啟 Spring Boot(Docker/ngrok 維持原狀,沒在跑的會補起來) ===" -ForegroundColor Cyan

$state = Read-DevState
$appPid = Resolve-ManagedProcessId -TrackedProcessId $state.springBootPid `
    -Port $AppPort -Kind "SpringBoot"
if ($appPid) {
    Stop-ProcessTree -ProcessId $appPid -Label "Spring Boot(舊)"
} else {
    $portOwner = Get-PortOwnerPid -Port $AppPort
    if ($portOwner) {
        Write-Host "port $AppPort 被未受管理的 PID $portOwner 使用，為避免誤殺已停止重啟。" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Spring Boot 本來就沒在跑。" -ForegroundColor DarkGray
}

# dev-start.ps1 本身具冪等性:Docker/ngrok 已在跑就沿用,8080 沒人聽才會真的啟動。
& "$PSScriptRoot\dev-start.ps1" -Profile $Profile
exit $LASTEXITCODE

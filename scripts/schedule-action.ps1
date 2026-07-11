# 對行程做決定。例:
#   .\schedule-action.ps1 3 confirm                                # 強制確認
#   .\schedule-action.ps1 3 park                                   # 先放 pending
#   .\schedule-action.ps1 3 reschedule "2026-07-15 14:00" "2026-07-15 15:00"  # 改時間重驗
#   .\schedule-action.ps1 3 complete / cancel / reject
param(
    [Parameter(Mandatory, Position = 0)][long]$Id,
    [Parameter(Mandatory, Position = 1)]
    [ValidateSet("confirm", "park", "reject", "cancel", "complete", "reschedule")]
    [string]$Action,
    [Parameter(Position = 2)][string]$Start,
    [Parameter(Position = 3)][string]$End
)
. "$PSScriptRoot\_common.ps1"

if ($Action -eq "reschedule") {
    if (-not $Start -or -not $End) {
        Write-Host "reschedule 需要新的開始與結束時間。" -ForegroundColor Yellow
        exit 1
    }
    $result = Invoke-Api PATCH "/api/schedules/$Id/reschedule" @{
        startAt = [DateTime]::Parse($Start).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        endAt   = [DateTime]::Parse($End).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    }
    if ($result.feasible) {
        Write-Host ("已改時間,可行,已確認 ✓(行程 {0})" -f $result.schedule.id) -ForegroundColor Green
    } else {
        Write-Host "新時間仍有問題:" -ForegroundColor Yellow
        $result.issues | ForEach-Object { Write-Host ("  ⚠ {0}" -f $_.message) -ForegroundColor Yellow }
    }
} else {
    $s = Invoke-Api PATCH "/api/schedules/$Id/$Action"
    Write-Host ("行程 [{0}] {1} → {2}" -f $s.id, $s.title, $s.status) -ForegroundColor Green
}

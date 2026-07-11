# 提出行程(系統會驗算可行性,可行才放行)。例:
#   .\add-schedule.ps1 "剪頭髮" "2026-07-15 11:00" "2026-07-15 12:00" -Place "台北理髮廳"
#   .\add-schedule.ps1 "線上會議" "2026-07-14 14:00" "2026-07-14 15:00"
param(
    [Parameter(Mandatory, Position = 0)][string]$Title,
    [Parameter(Mandatory, Position = 1)][string]$Start,
    [Parameter(Mandatory, Position = 2)][string]$End,
    # 行程地點名稱(選填;沒給就不做交通可行性檢查)
    [string]$Place
)
. "$PSScriptRoot\_common.ps1"

$body = @{
    title   = $Title
    startAt = [DateTime]::Parse($Start).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    endAt   = [DateTime]::Parse($End).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}
if ($Place) { $body.placeId = (Find-Place $Place).id }

$result = Invoke-Api POST "/api/schedules" $body
$s = $result.schedule

if ($result.feasible) {
    Write-Host ("行程 [{0}] {1} 可行,已確認 ✓" -f $s.id, $s.title) -ForegroundColor Green
} else {
    Write-Host ("行程 [{0}] {1} 有問題,尚未確認:" -f $s.id, $s.title) -ForegroundColor Yellow
    $result.issues | ForEach-Object { Write-Host ("  ⚠ {0}" -f $_.message) -ForegroundColor Yellow }
    Write-Host "你的選項:"
    Write-Host ("  改時間     .\schedule-action.ps1 {0} reschedule `"新開始`" `"新結束`"" -f $s.id)
    Write-Host ("  強制確認   .\schedule-action.ps1 {0} confirm" -f $s.id)
    Write-Host ("  先放pending .\schedule-action.ps1 {0} park" -f $s.id)
    Write-Host ("  放棄       .\schedule-action.ps1 {0} reject" -f $s.id)
}

# 回報「我到了某地點」(MANUAL_PING,視同進入)。例:
#   .\arrive.ps1 "全聯"
param(
    [Parameter(Mandatory, Position = 0)][string]$Place
)
. "$PSScriptRoot\_common.ps1"

$p = Find-Place $Place
$result = Invoke-Api POST "/api/location-events" @{
    eventType = "MANUAL_PING"
    latitude  = $p.latitude
    longitude = $p.longitude
    source    = "ps-arrive"
}

$hits = @($result.triggeredReminderIds)
if ($hits.Count -gt 0) {
    Write-Host ("已回報到達「{0}」→ 觸發 {1} 個提醒(id: {2})" -f $p.name, $hits.Count, ($hits -join ", ")) -ForegroundColor Green
} else {
    Write-Host ("已回報到達「{0}」→ 沒有觸發提醒(無綁定任務、都完成了、或在 debounce 視窗內)" -f $p.name)
}

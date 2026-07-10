# 回報「我離開某地點」(EXIT)。例:
#   .\leave.ps1 "我家"
param(
    [Parameter(Mandatory, Position = 0)][string]$Place
)
. "$PSScriptRoot\_common.ps1"

$p = Find-Place $Place
$result = Invoke-Api POST "/api/location-events" @{
    eventType = "EXIT"
    latitude  = $p.latitude
    longitude = $p.longitude
    source    = "ps-leave"
}

$hits = @($result.triggeredReminderIds)
if ($hits.Count -gt 0) {
    Write-Host ("已回報離開「{0}」→ 觸發 {1} 個提醒(id: {2})" -f $p.name, $hits.Count, ($hits -join ", ")) -ForegroundColor Green
} else {
    Write-Host ("已回報離開「{0}」→ 沒有觸發提醒" -f $p.name)
}

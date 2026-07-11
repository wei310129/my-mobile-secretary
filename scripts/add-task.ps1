# 新增任務。例:
#   .\add-task.ps1 "買排骨"
#   .\add-task.ps1 "繳學費" -Due "2026-07-15 09:00" -Priority HIGH -Description "跟老師說已繳"
param(
    [Parameter(Mandatory, Position = 0)][string]$Title,
    [string]$Description,
    [ValidateSet("LOW", "NORMAL", "HIGH")][string]$Priority = "NORMAL",
    # 本地時間,格式如 "2026-07-15 09:00";會自動轉 UTC。有給就會在該時間到點提醒
    [string]$Due
)
. "$PSScriptRoot\_common.ps1"

$body = @{ title = $Title; priority = $Priority }
if ($Description) { $body.description = $Description }
if ($Due) {
    $body.dueAt = [DateTime]::Parse($Due).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}

$task = Invoke-Api POST "/api/tasks" $body
Write-Host ("已建立任務 [{0}] {1}(狀態 {2})" -f $task.id, $task.title, $task.status) -ForegroundColor Green
if ($Due) { Write-Host ("到期提醒已排定:{0}(本地時間)" -f [DateTime]::Parse($Due)) }

# 顯示自動綁定結果(標題提到已登錄品項時,知識庫已自動綁好地點)
$rules = Invoke-Api GET "/api/tasks/$($task.id)/geofence-rules"
if (@($rules).Count -gt 0) {
    $places = Invoke-Api GET "/api/places"
    $nameById = @{}
    $places | ForEach-Object { $nameById[[long]$_.id] = $_.name }
    $bound = @($rules | ForEach-Object { $nameById[[long]$_.placeId] }) -join "、"
    Write-Host ("已自動綁定地點:{0}(到了就提醒)" -f $bound) -ForegroundColor Cyan
} elseif (-not $Due) {
    Write-Host "未綁定任何地點(標題沒有已登錄的品項)。可用 .\add-item.ps1 登錄品項,或 .\bind.ps1 手動綁。"
}

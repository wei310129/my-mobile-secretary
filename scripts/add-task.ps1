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

# 確認提醒已處理(催促鏈會停止)。例:.\done-reminder.ps1 3
param(
    [Parameter(Mandatory, Position = 0)][long]$Id
)
. "$PSScriptRoot\_common.ps1"

$r = Invoke-Api PATCH "/api/reminders/$Id/confirm"
Write-Host ("提醒 [{0}] 已確認(任務 {1} 記得也要完成:.\done-task.ps1 {1})" -f $r.id, $r.taskId) -ForegroundColor Green

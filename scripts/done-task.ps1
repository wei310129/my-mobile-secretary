# 確認任務完成(整個閉環結束,之後不再提醒)。例:.\done-task.ps1 1
param(
    [Parameter(Mandatory, Position = 0)][long]$Id
)
. "$PSScriptRoot\_common.ps1"

$t = Invoke-Api PATCH "/api/tasks/$Id/confirm"
Write-Host ("任務 [{0}] {1} 已完成 ✓" -f $t.id, $t.title) -ForegroundColor Green

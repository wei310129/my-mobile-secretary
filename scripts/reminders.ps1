# 列出全部提醒(新到舊)。例:.\reminders.ps1
. "$PSScriptRoot\_common.ps1"

$reminders = Invoke-Api GET "/api/reminders"
if (@($reminders).Count -eq 0) { Write-Host "目前沒有提醒。"; exit 0 }

$reminders | ForEach-Object {
    $at = if ($_.triggeredAt) { ([DateTimeOffset]::Parse($_.triggeredAt)).LocalDateTime.ToString("MM/dd HH:mm") } else { "-" }
    [PSCustomObject]@{
        Id   = $_.id
        任務  = $_.taskId
        狀態  = $_.status
        時間  = $at
        原因  = $_.triggerReason
    }
} | Format-Table -AutoSize

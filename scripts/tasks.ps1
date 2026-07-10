# 列出全部任務。例:.\tasks.ps1
. "$PSScriptRoot\_common.ps1"

$tasks = Invoke-Api GET "/api/tasks"
if (@($tasks).Count -eq 0) { Write-Host "目前沒有任務。"; exit 0 }

$tasks | ForEach-Object {
    $due = if ($_.dueAt) { ([DateTimeOffset]::Parse($_.dueAt)).LocalDateTime.ToString("MM/dd HH:mm") } else { "-" }
    [PSCustomObject]@{
        Id   = $_.id
        狀態  = $_.status
        優先  = $_.priority
        到期  = $due
        標題  = $_.title
    }
} | Format-Table -AutoSize

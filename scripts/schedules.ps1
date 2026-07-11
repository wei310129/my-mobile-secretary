# 列出行程。例:.\schedules.ps1  或只看 pending 池:.\schedules.ps1 PENDING
param(
    [Parameter(Position = 0)][ValidateSet("PROPOSED", "CONFIRMED", "PENDING", "REJECTED", "CANCELED", "COMPLETED")]
    [string]$Status
)
. "$PSScriptRoot\_common.ps1"

$path = "/api/schedules"
if ($Status) { $path += "?status=$Status" }
$schedules = Invoke-Api GET $path
if (@($schedules).Count -eq 0) { Write-Host "沒有行程。"; exit 0 }

# 地點對照
$places = Invoke-Api GET "/api/places"
$nameById = @{}
$places | ForEach-Object { $nameById[[long]$_.id] = $_.name }

$schedules | ForEach-Object {
    $placeName = if ($_.placeId) { $nameById[[long]$_.placeId] } else { "-" }
    [PSCustomObject]@{
        Id = $_.id
        狀態 = $_.status
        開始 = ([DateTimeOffset]::Parse($_.startAt)).LocalDateTime.ToString("MM/dd HH:mm")
        結束 = ([DateTimeOffset]::Parse($_.endAt)).LocalDateTime.ToString("HH:mm")
        地點 = $placeName
        標題 = $_.title
    }
} | Format-Table -AutoSize

# 把任務綁到地點(到地點時提醒)。例:
#   .\bind.ps1 -TaskId 1 -PlaceId 2
#   .\bind.ps1 -TaskId 1 -PlaceId 2 -Radius 300 -Trigger EXIT
param(
    [Parameter(Mandatory)][long]$TaskId,
    [Parameter(Mandatory)][long]$PlaceId,
    [int]$Radius = 200,
    [ValidateSet("ENTER", "EXIT")][string]$Trigger = "ENTER"
)
. "$PSScriptRoot\_common.ps1"

$rule = Invoke-Api POST "/api/tasks/$TaskId/geofence-rules" @{
    placeId      = $PlaceId
    radiusMeters = $Radius
    triggerType  = $Trigger
}
Write-Host ("已綁定:任務 {0} ← {1} 地點 {2}(半徑 {3} 公尺)" -f $rule.taskId, $rule.triggerType, $rule.placeId, $rule.radiusMeters) -ForegroundColor Green

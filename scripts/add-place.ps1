# 新增地點。例:
#   .\add-place.ps1 "全聯" 25.0330 121.5654 -Type "超市"
param(
    [Parameter(Mandatory, Position = 0)][string]$Name,
    [Parameter(Mandatory, Position = 1)][double]$Lat,
    [Parameter(Mandatory, Position = 2)][double]$Lon,
    [string]$Address,
    [string]$Type
)
. "$PSScriptRoot\_common.ps1"

$body = @{ name = $Name; latitude = $Lat; longitude = $Lon }
if ($Address) { $body.address = $Address }
if ($Type) { $body.type = $Type }

$place = Invoke-Api POST "/api/places" $body
Write-Host ("已建立地點 [{0}] {1}({2}, {3})" -f $place.id, $place.name, $place.latitude, $place.longitude) -ForegroundColor Green

# 列出全部地點。例:.\places.ps1
. "$PSScriptRoot\_common.ps1"

$places = Invoke-Api GET "/api/places"
if (@($places).Count -eq 0) { Write-Host "目前沒有地點。"; exit 0 }

$places | ForEach-Object {
    [PSCustomObject]@{
        Id   = $_.id
        名稱  = $_.name
        類型  = $_.type
        座標  = ("{0}, {1}" -f $_.latitude, $_.longitude)
        地址  = $_.address
    }
} | Format-Table -AutoSize

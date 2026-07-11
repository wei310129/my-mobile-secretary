# 列出全部品項知識。例:.\items.ps1
. "$PSScriptRoot\_common.ps1"

$items = Invoke-Api GET "/api/items"
if (@($items).Count -eq 0) { Write-Host "還沒有品項知識。用 .\add-item.ps1 登錄。"; exit 0 }

# 地點 id → 名稱對照
$places = Invoke-Api GET "/api/places"
$nameById = @{}
$places | ForEach-Object { $nameById[[long]$_.id] = $_.name }

$items | ForEach-Object {
    $placeNames = @($_.placeIds | ForEach-Object { $n = $nameById[[long]$_]; if ($n) { $n } else { "?($_)" } })
    [PSCustomObject]@{
        Id    = $_.id
        品項   = $_.name
        可購買於 = ($placeNames -join "、")
    }
} | Format-Table -AutoSize

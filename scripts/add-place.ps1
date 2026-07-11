# 新增地點。座標支援 Google Maps 直接貼上(逗號可留著),或用 -SameAs 共用既有地點的座標。例:
#   .\add-place.ps1 "全聯" 25.0330 121.5654 -Type "超市"
#   .\add-place.ps1 "我家" 24.982896852652157, 121.54288248224684
#   .\add-place.ps1 "特力屋" -SameAs "萬家福"    # 店中店:共用萬家福的座標
param(
    [Parameter(Mandatory, Position = 0)][string]$Name,
    # 接受:兩個數字、一串 "lat, lon" 文字、或 PowerShell 把逗號解讀成的陣列
    [Parameter(Position = 1)]$Lat,
    [Parameter(Position = 2)]$Lon,
    # 共用既有地點的座標(店中店:商場內的店、同一棟樓的多個店)
    [string]$SameAs,
    [string]$Address,
    [string]$Type
)
. "$PSScriptRoot\_common.ps1"

if ($SameAs) {
    # 店中店:直接複製既有地點的座標(找不到時 Find-Place 會列出現有地點)
    $src = Find-Place $SameAs
    $Lat = $src.latitude
    $Lon = $src.longitude
    Write-Host ("座標沿用「{0}」({1}, {2})" -f $src.name, $Lat, $Lon)
} elseif ($null -eq $Lat) {
    Write-Host "請給座標或 -SameAs。用法:" -ForegroundColor Yellow
    Write-Host "  .\add-place.ps1 `"全聯`" 25.0330 121.5654"
    Write-Host "  .\add-place.ps1 `"特力屋`" -SameAs `"萬家福`""
    exit 1
}

# 座標解析:把各種貼法統一成兩個 double
if ($Lat -is [array]) {
    # .\add-place.ps1 "我家" 24.98, 121.54 → 逗號讓 PowerShell 綁成陣列
    $Lon = $Lat[1]; $Lat = $Lat[0]
} elseif ("$Lat" -match ",") {
    # .\add-place.ps1 "我家" "24.98, 121.54" → 單一字串含逗號
    $parts = "$Lat" -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $Lat = $parts[0]; $Lon = $parts[1]
}
if ($null -eq $Lon) {
    Write-Host "缺少經度。用法:.\add-place.ps1 `"名稱`" 緯度 經度(或直接貼 Google Maps 的「緯度, 經度」)" -ForegroundColor Yellow
    exit 1
}
# 去掉殘留的逗號後轉數字;轉不動就報清楚的錯
try {
    $Lat = [double]("$Lat".TrimEnd(","))
    $Lon = [double]("$Lon".TrimEnd(","))
} catch {
    Write-Host "座標看不懂:Lat=$Lat Lon=$Lon。範例:.\add-place.ps1 `"我家`" 24.9828 121.5428" -ForegroundColor Yellow
    exit 1
}

$body = @{ name = $Name; latitude = $Lat; longitude = $Lon }
if ($Address) { $body.address = $Address }
if ($Type) { $body.type = $Type }

$place = Invoke-Api POST "/api/places" $body
Write-Host ("已建立地點 [{0}] {1}({2}, {3})" -f $place.id, $place.name, $place.latitude, $place.longitude) -ForegroundColor Green

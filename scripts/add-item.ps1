# 登錄品項知識:「這個東西在哪些地點買得到」。之後任務標題提到它就自動綁定。例:
#   .\add-item.ps1 "排骨" "全聯"
#   .\add-item.ps1 "醬油" "全聯","萬家福"
param(
    [Parameter(Mandatory, Position = 0)][string]$Name,
    # 可購買地點「名稱」,逗號分隔多個
    [Parameter(Mandatory, Position = 1)][string[]]$Places
)
. "$PSScriptRoot\_common.ps1"

# 地點名稱 → id(找不到會列出現有地點)
$placeIds = @($Places | ForEach-Object { (Find-Place $_).id })

$item = Invoke-Api POST "/api/items" @{ name = $Name; placeIds = $placeIds }
Write-Host ("已登錄品項 [{0}] {1} → 可購買於:{2}" -f $item.id, $item.name, ($Places -join "、")) -ForegroundColor Green
Write-Host "之後任務標題提到「$Name」會自動綁到這些地點,不用再手動 bind。"

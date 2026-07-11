# 共用設定與 API 呼叫 helper。各腳本以 . "$PSScriptRoot\_common.ps1" 載入。
$BaseUrl = "http://localhost:8080"

# 統一的 API 呼叫:body 一律以 UTF-8 bytes 送出,避免 Windows 主控台編碼弄壞中文
function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body = $null
    )
    $params = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 5
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        # 不用 Invoke-RestMethod:PS 5.1 對沒標 charset 的回應用 ISO-8859-1 解碼,中文會變亂碼。
        # 改拿原始 bytes 強制以 UTF-8 解碼再解析 JSON。
        $resp = Invoke-WebRequest @params -UseBasicParsing
        $bytes = $resp.RawContentStream.ToArray()
        if ($bytes.Length -gt 0) {
            [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
        }
    } catch {
        # 把後端的 ErrorResponse 印出來,而不是 PowerShell 的原始例外
        $resp = $_.Exception.Response
        if ($null -ne $resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $errBody = $reader.ReadToEnd()
            Write-Host "API 錯誤: $errBody" -ForegroundColor Red
        } else {
            Write-Host "連不上後端($BaseUrl)。請先啟動:docker compose up -d 然後 .\mvnw.cmd spring-boot:run" -ForegroundColor Red
        }
        exit 1
    }
}

# 依名稱找地點;找不到就列出現有地點
function Find-Place {
    param([Parameter(Mandatory)][string]$Name)
    $places = Invoke-Api GET "/api/places"
    $hit = @($places | Where-Object { $_.name -eq $Name })
    if ($hit.Count -eq 0) {
        Write-Host "找不到地點「$Name」。現有地點:" -ForegroundColor Yellow
        $places | ForEach-Object { Write-Host ("  [{0}] {1}" -f $_.id, $_.name) }
        exit 1
    }
    return $hit[0]
}

# 跟秘書講一句話,AI 幫你決定建任務還是行程。例:
#   .\say.ps1 "明天早上11點剪頭髮"
#   .\say.ps1 "要買醬油和排骨"
#   .\say.ps1 "下週三下午三點在內湖富邦大樓開會"
param(
    [Parameter(Mandatory, Position = 0)][string]$Text
)
. "$PSScriptRoot\_common.ps1"

$result = Invoke-Api POST "/api/intent" @{ text = $Text }

switch ($result.action) {
    "TASK_CREATED" {
        Write-Host ("✓ {0}(任務 [{1}])" -f $result.message, $result.task.id) -ForegroundColor Green
        # 顯示自動綁定結果
        $rules = Invoke-Api GET "/api/tasks/$($result.task.id)/geofence-rules"
        if (@($rules).Count -gt 0) {
            $places = Invoke-Api GET "/api/places"
            $nameById = @{}
            $places | ForEach-Object { $nameById[[long]$_.id] = $_.name }
            $bound = @($rules | ForEach-Object { $nameById[[long]$_.placeId] }) -join "、"
            Write-Host ("  已自動綁定地點:{0}" -f $bound) -ForegroundColor Cyan
        }
    }
    "SCHEDULE_CONFIRMED" {
        $s = $result.schedule.schedule
        $start = ([DateTimeOffset]::Parse($s.startAt)).LocalDateTime.ToString("MM/dd HH:mm")
        Write-Host ("✓ {0}({1} 開始,行程 [{2}])" -f $result.message, $start, $s.id) -ForegroundColor Green
    }
    "SCHEDULE_NEEDS_DECISION" {
        $s = $result.schedule.schedule
        Write-Host ("⚠ {0}(行程 [{1}])" -f $result.message, $s.id) -ForegroundColor Yellow
        $result.schedule.issues | ForEach-Object { Write-Host ("  ⚠ {0}" -f $_.message) -ForegroundColor Yellow }
        Write-Host ("  處理:.\schedule-action.ps1 {0} confirm|park|reject 或 reschedule" -f $s.id)
    }
    "CLARIFICATION_NEEDED" {
        Write-Host ("? {0}" -f $result.message) -ForegroundColor Yellow
    }
    "FALLBACK_TASK_CREATED" {
        Write-Host ("△ {0}(任務 [{1}])" -f $result.message, $result.task.id) -ForegroundColor Yellow
    }
}

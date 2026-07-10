# Phase 1E 生活測試工具

Phase 1E 目標:沒有 iOS App 的情況下,把系統當真的秘書用一週,記錄漏提醒/誤提醒/重複提醒(見 development-plan.md §12)。

## 前置

```powershell
docker compose up -d      # PostGIS + Redis
.\mvnw.cmd spring-boot:run  # 後端(local profile,提醒會跳 Windows 桌面通知)
```

## PowerShell 腳本

第一次先建地點與任務:

```powershell
cd scripts
.\add-place.ps1 "全聯" 25.0330 121.5654 -Type "超市"
.\add-place.ps1 "我家" 25.0400 121.5500
.\add-task.ps1 "買排骨" -Priority HIGH
.\bind.ps1 -TaskId 1 -PlaceId 1            # 到全聯提醒買排骨
.\add-task.ps1 "繳學費" -Due "2026-07-15 09:00"  # 時間型:到點自動提醒
```

日常使用:

```powershell
.\arrive.ps1 "全聯"       # 我到了 → 觸發綁定該地點的提醒(+桌面通知)
.\leave.ps1 "我家"        # 我離開了(觸發 EXIT 規則)
.\tasks.ps1               # 看任務
.\reminders.ps1           # 看提醒
.\done-reminder.ps1 3     # 確認提醒(停止催促)
.\done-task.ps1 1         # 完成任務(整個閉環結束)
```

提醒行為(數值見 application.yaml):
- 同一任務 10 分鐘內不重複提醒(debounce)
- 提醒後 15 分鐘沒確認 → 催促,最多 3 次
- 座標可從 Google Maps 上點右鍵複製

## api.http

VS Code 裝「REST Client」擴充後開 `api.http`,每個請求上方有 Send Request 可直接點。

## 一週紀錄建議

每天在筆記(或直接開 issue)記三件事:
1. 該響沒響(漏提醒)——最嚴重,務必記下當時情境
2. 不該響卻響(誤提醒/重複提醒)
3. 想要但沒有的功能(進 Phase 2+ 的真實需求清單)

# Phase 1E 生活測試工具

Phase 1E 目標:沒有 iOS App 的情況下,把系統當真的秘書用一週,記錄漏提醒/誤提醒/重複提醒(見 development-plan.md §12)。

## 前置

```powershell
docker compose up -d      # PostGIS + Redis
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local  # 後端(local profile,提醒會跳 Windows 桌面通知)
```

## 一鍵管理完整開發環境

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-start.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-status.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-restart.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-stop.ps1
```

`dev-start.ps1` 是開機後的標準入口，不必先手動開 Docker Desktop。它會依序：

1. 啟動 Docker Desktop；若 daemon 因 lingering Docker Desktop processes 無法建立，先用
   官方 `DockerCli -Shutdown`，再只清理 Docker Desktop／backend 殘留程序後重試。
   WSL 冷啟動或 VHDX 維護可能超過兩分鐘；修復後會再保留最多三分鐘的有界等待時間，期間每 15 秒
   顯示 frontend/backend 程序數，避免 engine 即將就緒時被過早判定失敗。
2. 啟動並等待 PostgreSQL、Redis healthy。
3. 從 LINE API 讀取目前 webhook 固定網域，以同一網域啟動 ngrok，避免誤開隨機網址。
4. 啟動 Spring Boot 與 failure-isolated AI Dispatcher。
5. 最後呼叫 LINE 官方 webhook test，直接驗證 `LINE -> ngrok -> Spring Boot`；只有失敗時
   才輸出 LINE、ngrok、本機 health 等分層診斷。

任何必要服務或端到端檢查不健康都會回傳非零 exit code。`dev-status.ps1` 預設也會執行
LINE 官方端到端測試；只想看本機狀態時可加 `-SkipLineWebhookTest`，本機開發不需要公開
webhook 時可在啟動加 `-NoNgrok`。若本機允許執行 PowerShell 腳本，也可在 `scripts`
目錄直接使用 `.\dev-restart.ps1`。

一般啟動與重啟會明確關閉 development feed、Dispatcher scheduler 與 Codex CLI adapter；
目前只啟動 Dispatcher 服務做健康檢查，不會自動開發。當 Dispatcher lane 處於 `STARTING`、
`RUNNING` 或 `RECOVERING` 時，`dev-restart.ps1` 與 `dev-stop.ps1` 會拒絕終止程序樹，避免把
正在執行的 Codex 連同 Dispatcher 一起強制殺掉。`dev-status.ps1` 會顯示目前 lane 狀態。

本機 Spring Boot 應用日誌寫入 `scripts\.logs\spring-boot.log`，每個檔案最多 10 MB、
保留 7 天且總量最多 100 MB。`spring-boot.out.log` / `spring-boot.err.log` 只保留 Maven
啟動器輸出；ngrok 只記錄警告以上。Postgres 與 Redis 的 Docker 日誌各自限制為
3 個 10 MB 檔案，避免長時間錯誤迴圈填滿 Docker Desktop 虛擬磁碟。

## Maven 安全執行

根專案的編譯與測試使用 `mvn-safe.ps1`，它會取得跨 PowerShell 程序的 Maven 鎖、整理子程序
的 `Path` 環境，並預設保留增量編譯與 `target` 內的評估輸出：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 '-Dtest=ReceiptServiceTest' test
```

只有已確認舊 class／annotation generated source 污染，或執行正式完整驗收時才使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -Clean test
```

`-Clean` 會刪除整個 `target`，包含存放其中的模型評估報告。若遇到 Windows 沙箱的
`Fatal Error: Cannot close compiler resources`，先確認沒有其他 Maven lifecycle，再於工作區
沙箱外使用相同參數重試；這類錯誤不得先用 clean 處理。

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
- 座標可從 Google Maps 上點右鍵複製(逗號不用刪,直接貼)

### 店中店/共用座標

商場內有多個店(例:萬家福裡有特力屋、outlet)時,用 `-SameAs` 讓它們共用座標:

```powershell
.\add-place.ps1 "萬家福" 24.9718 121.5423 -Type "量販"
.\add-place.ps1 "特力屋" -SameAs "萬家福" -Type "居家修繕"
.\add-place.ps1 "萬家福outlet" -SameAs "萬家福" -Type "outlet"
```

geofence 命中是**純空間判斷**(比距離,不比地點身分),所以人到商場
`.\arrive.ps1 "萬家福"` 一次,綁在特力屋、outlet 上的任務也會一起觸發。

## api.http

VS Code 裝「REST Client」擴充後開 `api.http`,每個請求上方有 Send Request 可直接點。

## 一週紀錄建議

每天在筆記(或直接開 issue)記三件事:
1. 該響沒響(漏提醒)——最嚴重,務必記下當時情境
2. 不該響卻響(誤提醒/重複提醒)
3. 想要但沒有的功能(進 Phase 2+ 的真實需求清單)

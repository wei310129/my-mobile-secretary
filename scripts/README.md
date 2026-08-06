# Phase 1E 生活測試工具

Phase 1E 目標:沒有 iOS App 的情況下,把系統當真的秘書用一週,記錄漏提醒/誤提醒/重複提醒(見 development-plan.md §12)。

## 前置

直接使用下方的 `dev-start.ps1`；它會從 primary Compose 定義驗證並復用固定的 shared-persistent
Postgres／Redis container，不應從 consumer worktree 直接執行 `docker compose up`。

## 一鍵管理完整開發環境

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-start.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-status.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-restart.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-stop.ps1
```

`dev-start.ps1` 是開機後的標準入口，不必先手動開 Docker Desktop。它會依序：

1. 透過 `start-docker-desktop-managed.ps1` 與 main 的 `start-managed-docker-desktop.ps1` 受限入口，
   驗證固定 executable／Authenticode identity、coordination mutex、CLI/context/daemon readiness 與
   bounded stability window；已 ready 時只回傳 receipt，失敗或逾時不停止、重啟、prune 或刪除 Docker 資源。
2. 驗證 coordination class、identity、Compose project/service、image、published port 與 volume contract，
   復用健康的 PostgreSQL／Redis；相符但停止時只允許受限 `docker start` 並等待 healthy。container name
   conflict 不會觸發 taskkill、repair、rename、replace 或重建。
3. 從 LINE API 讀取目前 webhook 固定網域，以同一網域啟動 ngrok，避免誤開隨機網址。
4. 啟動 Spring Boot 與 failure-isolated AI Dispatcher。
5. 最後呼叫 LINE 官方 webhook test，直接驗證 `LINE -> ngrok -> Spring Boot`；只有失敗時
   才輸出 LINE、ngrok、本機 health 等分層診斷。

任何必要服務或端到端檢查不健康都會回傳非零 exit code。`dev-status.ps1` 預設只收集本機狀態；
只有明確加 `-ExternalLineProbe` 才執行 LINE 官方端到端測試。若本機開發不需要公開 webhook，
可在啟動加 `-NoNgrok`。若本機允許執行 PowerShell 腳本，也可在 `scripts` 目錄直接使用
`.\dev-restart.ps1`。

Docker Desktop 的受限入口與環境 preflight：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-managed-docker-desktop.ps1 -TimeoutSeconds 180
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability DOCKER_TEST -RequireFresh
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability DOCKER_TEST -Async
```

`DOCKER_TEST -Async` 只建立受控 demand並交由 repository-owned monitor；sandbox detached child
不可靠時，agent 必須用可持續的 monitor／launcher execution取得 fresh receipt。environment receipt
只寫入 `var\environment-state\v1`，coordination receipt只寫入既有 LOCALAPPDATA coordination v1
root。Codex policy 只允許上述精確 project entrypoint，不允許任意 `Start-Process`、exe、arguments、
Docker CLI destructive command或 Docker 資料資源清理。

一般啟動與重啟會明確關閉 development feed、Dispatcher scheduler 與 Codex CLI adapter；
目前只啟動 Dispatcher 服務做健康檢查，不會自動開發。當 Dispatcher lane 處於 `STARTING`、
`RUNNING` 或 `RECOVERING` 時，`dev-restart.ps1` 與 `dev-stop.ps1` 會拒絕終止程序樹，避免把
正在執行的 Codex 連同 Dispatcher 一起強制殺掉。`dev-status.ps1` 會顯示目前 lane 狀態。

`dev-stop.ps1` 會在每個 component 驗證停止後立刻原子更新該 component 的 `.dev-state.json`，並
釋放／reconcile 對應 ownership；shared-persistent container 與 volume 永遠保留，`-Docker` 只做
唯讀 contract verification，`-RemoveVolumes` 會 fail closed。tracked launcher 即使已失去 port，也只有在 PID、process start time、精確 worktree command
或 active coordination operation 全部吻合時才可停止；PID reuse、跨 worktree、另一個 active owner
或 query 不可判定時一律 fail closed。

Codex 需要處理另一個 `var\worktrees` worktree 的 stale managed process 時，只能使用不接受 PID 的
受限入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stop-managed-worktree-process.ps1 `
  -Worktree D:\my-project\my-mobile-secretary\var\worktrees\calendar-w11 `
  -Component SpringBoot
```

此入口會先讀目標 worktree state／可信 receipt，核對同一 worktree 的 coordination resource與其他
active operation，再委派正式 `Stop-ProcessTree`。它不接受任意 PID，也不清 log、DB、volume或舊 evidence。

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

Codex policy 只核准這個 repository-owned entrypoint；裸 `mvnw.cmd` 沒有 allow rule。runner 只接受
單一 allowlisted lifecycle goal（compile、test-compile、test、package、verify或spotless:check），
拒絕 `-f`／`--file` 與會把 execution/write root 導出 worktree 的 Maven properties。coordination
operation／receipt 只由同一 library 原子寫入固定 LOCALAPPDATA coordination v1 tree，resource key
由 runner 所在 worktree 推導，不能由呼叫者指定。

linked worktree 的 Maven version metadata 也由 runner 自動處理：它以 `git rev-parse --git-dir`
驗證目前 worktree 的 Git metadata，將 plugin anchor 限定在該 metadata 的既有 `logs` 目錄，
再透過 `MMS_MAVEN_GIT_DIRECTORY` 傳給 `git-commit-id-maven-plugin`。因此 `/actuator/info`
的 SHA／commit count 不會誤讀 primary worktree；anchor 缺失時 Maven 會 fail closed，不需要手動
設定環境變數，也不會修改 `.git` metadata。

同一 project rules 以 `forbidden` 阻擋 `git reset`、`git clean`、會覆寫檔案的 `git checkout`、
`git restore` 與 `git stash`。mixed-owner worktree 必須改用新 branch／獨立 worktree或經 review 的
精確 patch，不得用會改寫、刪除或隱藏既有變更的指令處理。

只有已確認舊 class／annotation generated source 污染，或執行正式完整驗收時才使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -Clean test
```

`-Clean` 會刪除整個 `target`，包含存放其中的模型評估報告。若遇到 Windows 沙箱的
`Fatal Error: Cannot close compiler resources`，先確認沒有其他 Maven lifecycle，再於工作區
沙箱外使用相同參數重試；這類錯誤不得先用 clean 處理。

## Tooling regression gate

不啟動 Docker、Spring Boot、ngrok 或 Maven runtime 的 PowerShell tooling gate：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\tests\tooling-implementation-test.ps1
```

此 gate 會依序驗證 coordination、managed process、environment preflight/review、shared-container
contract、worktree evidence ownership 與 startup rollback；shared Docker 測試使用 fake adapter，
不會刪除、重建或改動真實 container／volume。

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

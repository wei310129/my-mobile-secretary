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

`dev-restart.ps1` 會保留shared databases，但會讓Spring Boot與ngrok一起進入新的service generation；
不重用舊generation的tunnel receipt。`-NoNgrok`會停止舊managed tunnel且不重新啟動。

`dev-start.ps1` 會把本次 build input 的 production-content fingerprint、HEAD、registered worktree
identity 與 service generation 寫入 ignored runtime state。`dev-status.ps1` 重新計算相同 fingerprint：
dirty worktree 只在內容完全相符時回報 `VERIFIED_DIRTY`；build 後變更 `src/main`、主 `pom.xml` 或
Dispatcher production input 會回報 `STALE`，純文件變更不會誤判 runtime stale。缺少 receipt、跨
worktree、跨 generation 或舊 runtime metadata 一律要求 restart，不以 `DIRTY` allowlist 放行。

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

外部 provider capability 必須先由本輪明確授權產生最長 15 分鐘的 scoped receipt，再把 receipt 路徑、
provider、operation class 與 scope 一起傳給 `dev-preflight.ps1`。receipt 綁定 issuer、repository、
registered worktree、caller、contract fingerprint 與 nonce；任一欄位不符即拒絕。例：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\new-external-authority-receipt.ps1 `
  -Provider TDX -OperationClass ROUTE_QUERY -Scope READ_ONLY -UserAuthorizedThisTurn -Json
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability EXTERNAL_PROVIDER `
  -ExternalProvider TDX -ExternalOperationClass ROUTE_QUERY -ExternalScope READ_ONLY `
  -AuthorityReceipt <receipt-path> -RequireFresh
```

TDX／Google route query、LINE connectivity 與 Booking availability 是 `READ_ONLY`；inventory mutation、
booking creation、payment、cancellation 與 refund 是獨立 `MUTATION` operation。read-only receipt 不可
授權任何 mutation，也不可跨 caller 或 worktree 重用。

`dev-start.ps1` 成功發布 runtime generation 後，會先為已驗證 READY 的 Docker/shared infrastructure 寫入
`DOCKER_SHARED_INFRASTRUCTURE_READY` observation receipt，再為實際 host-managed `RUNTIME_START` 寫入短效
operation participation receipt；官方 LINE probe 成功時另為 `LINE_CONNECTIVITY_PROBE` 寫入 receipt。
`dev-status.ps1 -ExternalLineProbe` 只有 probe 成功才寫入 LINE receipt。這些 receipt 使用
`MMS_MANAGED_OPERATION_V1`，綁定 repo／Git directory／registered worktree／machine alias／capability／
operation／generation／host caller／contract／snapshot sequence／expiry／nonce，並且只有
`REVIEW_OBSERVATION_ONLY` 權限。

Automatic review 可用 matching receipt 關閉「只做啟動前 probe、未參與 operation」的 sandbox
`PREFLIGHT_CALLER_ACCESS_DENIED` 或 `PREFLIGHT_HOST_READY_CALLER_BLOCKED` 歷史 issue，同時保留 typed
audit history。實際參與 operation 的 caller 必須由正式 report 標示
`-Participation OPERATION_PARTICIPANT`，仍須自己的 ready evidence；host receipt 不會授權 sandbox
mutation，也不能解鎖 `SOURCE_WRITE`、其他 Docker operation、external provider、booking 或 payment。

同一 canonical issue 若已有舊 contract／generation 的 `FIXED` managed supersession，review 不會把它
當成任意可覆寫資料。只有 retained historical receipt 與舊 typed evidence 先通過 repo／worktree／machine／
caller／capability／operation／snapshot／authority validator，再由 current fresh receipt 通過完整 fence，
才會原子續期 current evidence。原始 observation contract、resolution lineage 與歷史 receipt 都保留；
同一 current receipt 重跑不重寫 ledger。tampered evidence、participant 或未核准 issue code 一律 fail closed。

reuse 既有健康 runtime 時，generation 一律從已原子寫入的 `.dev-state.json` 讀取，不得以 StrictMode
存取可能不存在的 `$stateUpdates.serviceGeneration`。任何 managed receipt 發布失敗都會保留已健康的
resource，但 `dev-start.ps1` 必須回傳 nonzero，不能把缺 evidence 的啟動宣稱成功。

一般啟動與重啟會明確關閉 development feed、Dispatcher scheduler 與 Codex CLI adapter；
目前只啟動 Dispatcher 服務做健康檢查，不會自動開發。當 Dispatcher lane 處於 `STARTING`、
`RUNNING` 或 `RECOVERING` 時，`dev-restart.ps1` 與 `dev-stop.ps1` 會拒絕終止程序樹，避免把
正在執行的 Codex 連同 Dispatcher 一起強制殺掉。`dev-status.ps1` 會顯示目前 lane 狀態。

`dev-stop.ps1` 會在每個 component 驗證停止後立刻原子更新該 component 的 `.dev-state.json`，並
釋放／reconcile 對應 ownership；shared-persistent container 與 volume 永遠保留，`-Docker` 只做
唯讀 contract verification，`-RemoveVolumes` 會 fail closed。tracked launcher 即使已失去 port，也只有在 PID、process start time、精確 worktree command
或 active coordination operation 全部吻合時才可停止；PID reuse、跨 worktree、另一個 active owner
或 query 不可判定時一律 fail closed。

沒有ACTIVE operation但仍有matching typed ownership receipt時，read-only診斷只會分類為
`ORPHAN_EXACT_RECONCILABLE`；正式stop會再簽發最長30秒、component-scoped、single-use authority並立即
重驗PID、creation time、command fingerprint、worktree與service generation。正常停止只終止已證明root的
descendant tree，使用exact `Stop-Process`與bounded verification，不以`taskkill`作成功路徑；中途失聯可由
保留的receipt、released operation與expected-PID state fence安全重播。

Codex 需要處理另一個 `var\worktrees` worktree 的 stale managed process 時，只能使用不接受 PID 的
受限入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stop-managed-worktree-process.ps1 `
  -Worktree D:\my-project\my-mobile-secretary\var\worktrees\calendar-w11 `
  -Component SpringBoot
```

此入口會先讀目標 worktree state／可信 receipt，核對同一 worktree 的 coordination resource與其他
active operation，再委派正式exact managed process-tree stop。它不接受任意 PID，也不清 log、DB、volume或舊 evidence。

本機 Spring Boot 應用日誌寫入 `scripts\.logs\spring-boot.log`，每個檔案最多 10 MB、
保留 7 天且總量最多 100 MB。`spring-boot.out.log` / `spring-boot.err.log` 只保留 Maven
啟動器輸出；ngrok 只記錄警告以上。Postgres 與 Redis 的 Docker 日誌各自限制為
3 個 10 MB 檔案，避免長時間錯誤迴圈填滿 Docker Desktop 虛擬磁碟。

新啟動 ngrok 的 ownership receipt 不會只相信 PID、process name 或尚未完成的 WMI snapshot。
`dev-start.ps1` 以Windows job breakaway與handle allowlist啟動managed child；child只繼承兩個worktree內
log handles，不繼承Codex／WSL caller handles。啟動前記錄launch time，並在最長1.5秒的bounded window內等待
同一 PID 的 `ExecutablePath`、完整 `CommandLine` 與 start time 就緒。只有 exact `ngrok.exe`、本輪
arguments、registered worktree、application port 與 launch window 全部相符時才發布 receipt；錯誤
command、不同 executable、PID reuse、重複發布及未明列的 wrapper／child 都立即 fail closed。
receipt 只保存不可逆 executable／sanitized-command contract fingerprint，不保存 webhook host 或 raw command。
Spring Boot另須bounded actuator readiness；ngrok須matching tunnel contract。兩者在receipt publication boundary
及durable state write後都會重驗exact process與readiness；消失或不再ready會把receipt標為`REVOKED`，
rollback本輪新啟動PID與generation state，並回傳nonzero。

若 `Win32_Process` 在目前 caller context 不可用，WMI 會明確分類為 `UNAVAILABLE`，不再偽裝成
只有 PID/name/start time 的 ready snapshot。Windows 會改由同 caller 的 process handle 使用
`QueryFullProcessImageName`、`GetProcessTimes` 與 `NtQueryInformationProcess(ProcessCommandLineInformation)`
取得 exact image path、creation time 與 command line；此來源仍須通過相同 ngrok publication contract。
WMI 與 native source 都有完整資料時必須一致，否則回 `PROCESS_IDENTITY_SOURCE_MISMATCH` 並 fail closed。
錯誤結果只保留 typed reason code，不輸出 executable path、command line 或 webhook host。
兩個來源的 creation time 與 ownership fingerprint 統一使用 UTC、向下截斷到微秒的 canonical value；
這是 WMI 可可靠表達的最高共同精度。相同程序在 native FILETIME 多出 100ns ticks 時不會誤判，差異達
1 微秒、PID reuse 或任何 component／worktree／executable／command／generation 不符仍 fail closed。
新 receipt 明載 `managed-process-command-v2` 與 `UTC_MICROSECOND_TRUNCATED`；沒有版本欄位的既有 v1
receipt 只在全部 typed identity 相符且 start time canonical-equivalent 時接受窄幅相容驗證。

managed process replay scan 允許 coordination `receipts` 目錄保留沒有 `action` 的合法舊 schema與其他
receipt type，不會刪除、改寫或把它們當成 process replay。任何宣稱 `action=managed-process-start` 的
receipt 則必須先通過完整 typed schema：component、正整數 PID、可解析的 process start time、worktree與
resource 缺一不可，且欄位型別必須精確；invalid JSON或 malformed managed receipt一律 fail closed。
只有 component、PID與 canonical start time全部吻合才判定 exact generation replay。ownership read使用同一 validator，
錯誤只回傳固定分類，不輸出 receipt內容或本機路徑。

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

此 gate 會依序驗證 coordination、managed process durability／orphan／caller classification、environment preflight/review、shared-container
contract、worktree evidence ownership 與 startup rollback；shared Docker 測試使用 fake adapter，
不會刪除、重建或改動真實 container／volume。

## Producer handoff automation

`producer-handoff.ps1` 將產品 PR 合併後的 state-only handoff 變成可重播、fail-closed 的資料契約。
v1 只 allowlist `TR-DESKTOP-ROUTE-BENCHMARK-START`，request 只能提供 `requestId`、`eventId` 與一個
active-plan evidence；state path、lane、status、通知與 consumer action 都由
`.github/producer-handoff-policy.json` 決定，request 不能覆寫。

兩條 workflow 的邊界如下：

- `producer-handoff-request.yml` 只監聽 request manifest；kill switch 關閉時只做 dry-run。
- `producer-handoff-state.yml` 只處理 `automation/producer-handoff/**` 的 state＋receipt PR，並等待
  repository 既有 required checks；不新增一般產品 PR 的 required check。
- manual dispatch 會向 GitHub API 重驗 PR、merge SHA 與 merge timestamp；state validation／rebuild
  只執行 PR base／`main` 的 trusted engine 與 policy，不執行 PR head 提供的 tooling code。
- 目標 trigger 未改但其他 main state 前進時最多自動 rebuild 一次；目標 trigger、owner 或 schema
  改變就 fail closed，不覆蓋 producer 的新決策。
- Git state 合併後才更新 `[Coordination] Producer handoff receipts` Issue；Issue 暫時失敗可直接 rerun
  finalizer；kill switch 關閉時 finalizer 也不做 Issue mutation。READY state 不回滾，但 consumer 仍須
  看到 receipt、fetch、驗證 SHA 後才能 ACK。Merge policy 會強制 active desktop ACK 的 event 與
  `publishedSha` 對應同名 laptop `READY`／`CONSUMED` producer state。

Repository 設定必須先建立 `tooling-producer` environment，允許 `main` 與
`automation/producer-handoff/**`，並設定：

- repository variable `TOOLING_PRODUCER_ENABLED=false`（完成 dry-run 後才改 `true`）
- repository/environment variable `TOOLING_PRODUCER_APP_ID`
- environment secret `TOOLING_PRODUCER_APP_PRIVATE_KEY`

GitHub App 只安裝於本 repository，權限限定 Contents、Pull requests、Issues read/write與 Metadata read；
不得有 direct-push 或 branch-protection bypass。啟用前先執行本機零 mutation contract gate：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\tests\producer-handoff-test.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\tests\producer-handoff-workflow-test.ps1
```

Live pilot 仍需由 laptop-owned coordination PR 先加入 PENDING/BLOCKED trigger與單一 request manifest；
tooling branch 不得直接修改 producer state或替 consumer 開閘。

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

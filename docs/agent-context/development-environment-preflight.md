# 開發環境 Preflight 與多機一致性契約

所有 session 都必須先依工作內容宣告 capability，取得環境快照後再讀寫、測試或操作服務。目標是讓
相同 repository 指令在各主機有一致行為；版本、vendor 或安裝路徑不同但代表指令通過時，不做無效調整。

## Session 入口

```powershell
# 唯讀／規劃 session：可使用快取並非同步刷新
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability READ_ONLY -Async

# 已知本輪稍後會寫碼或跑 Maven：盤點期間先非同步預熱
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability SOURCE_WRITE -Async
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability MAVEN -Async

# 實際 gate 前消費五分鐘內快照；快取缺少／逾期才同步補探測
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability MAVEN
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability DOCKER_TEST
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability DEV_RUNTIME
powershell -ExecutionPolicy Bypass -File .\scripts\dev-preflight.ps1 -Capability LINE_E2E
```

`-RequireFresh` 只用於 release review、疑似狀態剛改變或明確要求重新觀測的診斷；一般 gate 應消費
非同步產生且仍在 TTL 內的快照，避免把預檢重新變成主流程的同步等待。

Codex sandbox 會回傳 `asyncLauncher=AGENT_ASYNC_TOOL_REQUIRED`，因為 sandbox job object 可能在父工具
結束時回收 detached child。此時 `-Async` 只負責 durable demand；agent 必須立刻用自身可持續的非同步
tool execution 執行 `dev-environment-monitor.ps1 -Capability <CAPABILITY> -Once`，並同時繼續唯讀盤點。
無法啟動時必須回報，不得把 queued demand 寫成已刷新。

固定 capability 為 `READ_ONLY`、`SOURCE_WRITE`、`MAVEN`、`DOCKER_TEST`、`DEV_RUNTIME`、
`LINE_E2E`、`EXTERNAL_PROVIDER`。唯讀工作遇到 `UNKNOWN` 可降級並回報；source mutation、Maven、
Docker、runtime、LINE 或 external operation 缺少有效 receipt 時必須 `BLOCKED`。

## 狀態與校準

- `MATCH`：版本與行為一致。
- `COMPATIBLE_DRIFT`：版本、vendor 或路徑不同，但代表指令通過；不調整。
- `ACTION_REQUIRED`：已造成指令失敗、結果不同或同步等待；需修復。
- `UNKNOWN`：權限、離線或 timeout；不可宣稱 READY。
- `HOST_READY_CALLER_BLOCKED`：主機可用，但目前 sandbox／caller 無權使用。
- `DESKTOP_NOT_RUNNING`：Docker Desktop lifecycle 尚未被受限 managed launcher 穩定觀測。
- `DAEMON_NOT_READY`：CLI／context 可見但 daemon 尚未通過 bounded stability window。
- `CALLER_ACCESS_DENIED`：daemon 存在但目前 caller 無法存取。
- `AGENT_ASYNC_TOOL_REQUIRED`：sandbox 不得以 detached child 冒充 managed Docker lifecycle，必須由 agent 持有 monitor execution。
- `SHARED_CONTAINER_STOPPED`：固定 shared-persistent container 合約相符但已停止，僅允許 `docker start` 後 bounded health wait。
- `SHARED_CONTAINER_IDENTITY_MISMATCH`：coordination labels、Compose project/service、image、published port 或 volume 合約不符，必須 fail closed 且零 mutation。
- `SHARED_CONTAINER_UNHEALTHY`：合約相符但 health 尚未通過。
- `WORKTREE_COMPOSE_NAME_CONFLICT`：同名 container 的 ownership／合約無法安全證明，不得 repair Docker Desktop、taskkill 或 replace container。

Java 必須同時通過 `java -version`、`JAVA_HOME\bin\java.exe` 與 `mvnw.cmd -v`。Git、Docker、
PowerShell 等只在代表指令失敗或已有相容性證據時才更新。若 Java 的使用者層 `JAVA_HOME` 失效，先以
有效 JDK 21 通過 Maven 驗證，再使用 `dev-environment-repair.ps1 -PersistUserJavaHome` 修正；不要求
各機 JDK 實體路徑、vendor 或 patch 完全相同。

安裝 monitor 後，已驗證的 PATH JDK 21 可在 host 安全修復失效的使用者層 `JAVA_HOME`；修復前後都會
以 `java -version` 與 `mvnw.cmd -v` 驗證並留下 receipt。已開啟的 host／sandbox process 若仍繼承舊值，
先使用 process-local 修復；只有 host 可改使用者層設定。

狀態預設存於主 worktree 的 `var/environment-state/v1`，該目錄已由 `/var/` 排除版控。同一 clone 的
host、sandbox 與 worktree 共用 demand、問題與 review 狀態，但每台機器分開保存
caller × capability 快照（例如 `snapshot-host-maven.json`、`snapshot-sandbox-docker_test.json`），並以
`snapshot-host.json`／`snapshot-sandbox.json` 保存 latest 摘要。不同 capability 與 caller 不得互相冒充 READY。
也不得把 hostname、帳號、PID、絕對路徑、webhook URL、credential 或秘密發布到 GitHub。

## 自適應監測與跨機狀態

`install-dev-environment-monitor.ps1` 建立登入與每 15 分鐘觸發的 Windows task。沒有 active demand 時
monitor 只做必要的輕量檢查；Docker、runtime 與 LINE 只在 upcoming capability 明示需要時探測。
每個 caller／capability demand 保留 30 分鐘；排程選擇仍有效的最高層級需求，monitor 自己不延長 TTL，
避免另一個 READ_ONLY session 覆寫重型預熱或讓一次 Maven／Docker 需求永久續期。
排程遵循 Windows 的電池節能限制；互動 session 的 `-Async` preflight 仍會立即刷新。
ordinary start 最多啟動 Docker Desktop 並等待，不得自動 shutdown、kill 或重啟共享 Docker backend。
主 worktree Compose 定義的 `mms-postgres`、`mms-redis` 與 Dispatcher PostgreSQL 是固定
`shared-persistent` identity；任何 worktree 的 `dev-start.ps1`／`dev-restart.ps1` 都先以 coordination
class、identity、Compose project/service、image、published port 及 volume contract 驗證，再復用健康 container。
停止的相符 container 只可由 managed lifecycle 以 `docker start` 啟動；不相符或 ownership 不明時必須
分類失敗並保持 container／volume 不變。`dev-restart.ps1 -Full` 也只重啟應用程式與 ngrok，轉交並重新驗證
shared infrastructure 決策。

每台機器完成一次 `gh auth login` 後，以
`dev-environment-github.ps1 -BootstrapIssue` 尋找或建立同一個 coordination Issue，monitor 只原地更新該機器的
去識別 comment。GitHub 摘要 TTL 為 30 分鐘，只是 readiness 提示；`origin/main` state、published SHA、
ownership、schema token 與使用者授權仍是唯一權限真相。

每台 Windows 開發機只需做一次（alias 必須唯一）：

```powershell
# 筆電使用 laptop；桌電改成 desktop
powershell -ExecutionPolicy Bypass -File .\scripts\install-dev-environment-monitor.ps1 -MachineAlias laptop
gh auth login --hostname github.com --git-protocol https --web
powershell -ExecutionPolicy Bypass -File .\scripts\dev-environment-github.ps1 -BootstrapIssue
powershell -ExecutionPolicy Bypass -File .\scripts\install-dev-environment-monitor.ps1 -MachineAlias laptop -PublishGitHub
```

`gh` 尚未登入時 local snapshot／preflight／repair 仍可用，但跨機摘要必須標為 blocked，不得改用 repo token。

## 問題回報與 Release review

不符合情況使用 `dev-environment-report.ps1` 依 code、capability 與 caller context 去重。一般功能 session
只登記問題，不順手改 tooling。Release review 重新執行目前版本的 probe，分類為 `OPEN`、`FIXED`、
`RESOLVED_BY_PROJECT_EVOLUTION` 或 `ACCEPTED_LIMITATION`；自然消失的問題也必須
附重新驗證證據。

issue fingerprint 綁定 typed uppercase code、capability、caller 與 registered worktree。`FIXED` 與
`RESOLVED_BY_PROJECT_EVOLUTION` 只能由 matching caller/capability/worktree 的 fresh ready snapshot
產生結構化 resolution evidence；不能用 host snapshot 代替 sandbox。`ACCEPTED_LIMITATION` 只能使用
tooling allowlist 內的 policy code，普通 observation 不會靜默重開，但 contract 演進後必須重新審查。
正式入口範例：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-environment-report.ps1 `
  -Code PREFLIGHT_CALLER_ACCESS_DENIED -Capability DOCKER_TEST `
  -Expected 'matching caller is ready' -Actual 'caller probe denied' -RecheckKind CAPABILITY

powershell -ExecutionPolicy Bypass -File .\scripts\dev-environment-report.ps1 `
  -Code ENVIRONMENT_MONITOR_FAILED -Capability READ_ONLY -Resolve -ResolutionStatus FIXED
```

report 會遮蔽 hostname、帳號、absolute path、URL 與 credential-like 值；參數組合不完整、任意 issue
code、任意 limitation 文字與 mismatched caller evidence 都 fail closed。

### Managed operation caller participation

`DOCKER_TEST`、`DEV_RUNTIME` 與 `LINE_E2E` 額外區分 `PROBE_ONLY` 與 `OPERATION_PARTICIPANT`。sandbox 在啟動前進行
capability probe，但沒有實際操作 Docker、runtime 或 LINE 時，`PREFLIGHT_CALLER_ACCESS_DENIED`／
`PREFLIGHT_HOST_READY_CALLER_BLOCKED` 會保留為可稽核的 probe-only issue。其後若正式 managed host lifecycle
完成同一 registered worktree 的 Docker/shared-infrastructure readiness、runtime start 或 LINE connectivity probe，可由短效
`MMS_MANAGED_OPERATION_V1` receipt 將該 issue typed 地標為 `FIXED`，resolution kind 為
`MANAGED_OPERATION_SUPERSESSION`；這不是 accepted limitation，也不是 host snapshot 冒充 sandbox readiness。

receipt 綁定 repository、Git directory、registered worktree、machine alias、capability、operation、service
generation、host operation caller、contract fingerprint、fresh host snapshot sequence、15 分鐘 expiry 與 nonce。
restart／generation、worktree、capability、operation、contract 或 caller 任一不符即拒絕。receipt 的
`authorityClass=REVIEW_OBSERVATION_ONLY`，只可用於 Automatic review issue resolution，不授權
`SOURCE_WRITE`、Docker mutation、external provider query、Calendar／Booking mutation、payment、cancellation 或 refund。

Docker receipt 的 exact operation 是 `DOCKER_SHARED_INFRASTRUCTURE_READY`，只在正式 host lifecycle 已驗證
Docker daemon與 shared-container contract 為 `READY`，且 durable service generation 已寫入後簽發。它不能
啟動、停止、建立、替換或刪除 container／volume，也不能把 sandbox caller 變成 Docker mutation caller。

若 caller 實際參與 resource operation，正式 report 必須使用
`-Participation OPERATION_PARTICIPANT -RecheckKind MANAGED_OPERATION`；該 issue 仍要求 matching caller
ready evidence，不能被另一 caller 的 managed receipt 關閉。舊 schema 的 typed `PREFLIGHT_*` issue 只在上述
兩個 access-denied code 與 capability/operation 完全匹配時視為 probe-only，resolution 後仍保留原始 caller、
occurrence 與 observation contract 供稽核。

Calendar W10 closure 使用 `Manual` review；Calendar W11 起每個 major release／wheel closure 使用
`Automatic` review，缺少新鮮、零 open blocker 且 contract fingerprint 相符的 evidence 就不得標
`PASS`／`MERGED`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-environment-review.ps1 `
  -ReleaseGate calendar-w10 -Mode Manual -Capability READ_ONLY,MAVEN `
  -TargetWorktree $PWD -EvidencePath docs\exec-plans\evidence\development-environment\calendar-w10.json

powershell -ExecutionPolicy Bypass -File .\scripts\assert-dev-environment-review.ps1 `
  -ReleaseGate calendar-w10 -Mode Manual -MachineAlias laptop -RequiredCapability READ_ONLY,MAVEN `
  -TargetWorktree $PWD -EvidencePath docs\exec-plans\evidence\development-environment\calendar-w10.json
```

W11 起把 `Mode` 改為 `Automatic`，並在合併後的 CI／release verification 使用 `-RequireTracked`。
每台參與 release 的 active machine 各產生一份檔案；整合 gate 必須逐一用 `-MachineAlias` 與
`-RequiredCapability` 驗證，不能拿筆電 evidence 代替桌電，也不能用 READ_ONLY 代替實際用過的 Maven／Docker。
Automatic review 只納入本輪 `-Capability` 明列且實際使用的 capability；歷史 EXTERNAL_PROVIDER 或
Docker probe 不得迫使未使用它們的一般 release session 取得新權限，但同一 worktree、本輪 required
capability 的未解 issue 仍會 fail closed。
Tracked review evidence 只保存 opaque repository／worktree identity、branch／SHA 與 repository-relative
evidence path；不得寫入 hostname、帳號、worktree absolute path、Git common directory 或 primary root。
`-TargetWorktree` 必須是同一 Git common-dir 下已 registered 的 worktree；`EvidenceRoot` 與
`EvidencePath` 會以 canonical path 驗證，只允許寫入該 target worktree 的
`docs/exec-plans/evidence/development-environment`，path escape、跨 repository、未註冊 worktree
與 machine alias 不符一律 fail closed。root evidence 不得冒充 consumer worktree evidence。
`EXTERNAL_PROVIDER` 使用正式短效 authority receipt。receipt 至少綁 issuer、issuedAt/expiresAt、
repository/worktree、caller、capability、provider、operation class、READ_ONLY/MUTATION scope、nonce
與 contract fingerprint；TDX/Google read-only route receipt 絕不涵蓋 Calendar mutation、booking、
payment、cancellation 或 refund。

## Context 壓縮提醒點

- tooling implementation regression gate 完成且沒有未定位失敗時，才適合壓縮 context；續作摘要必須保留已驗證的 shared-container contract、managed lifecycle、worktree evidence ownership、測試結果、尚未取得的 live receipts 與下一個提交／發布步驟。
- fresh `SOURCE_WRITE`、`MAVEN`、`DOCKER_TEST`、`DEV_RUNTIME`、`LINE_E2E` receipts 與 Automatic review evidence 完成後，才適合進入交付摘要；續作摘要必須保留 target worktree identity、contract fingerprint、evidence path、open blocker 數量、branch／SHA 與尚未取得的 merge 授權。
- 若仍在處理 Docker lifecycle、shared container ownership、generation rollback 或 evidence path 驗證失敗，不得以 context 壓縮取代定位、修復與安全 gate。

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

Calendar W10 closure 使用 `Manual` review；Calendar W11 起每個 major release／wheel closure 使用
`Automatic` review，缺少新鮮、零 open blocker 且 contract fingerprint 相符的 evidence 就不得標
`PASS`／`MERGED`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-environment-review.ps1 `
  -ReleaseGate calendar-w10 -Mode Manual -Capability READ_ONLY,MAVEN `
  -EvidencePath docs\exec-plans\evidence\development-environment\calendar-w10.json

powershell -ExecutionPolicy Bypass -File .\scripts\assert-dev-environment-review.ps1 `
  -ReleaseGate calendar-w10 -Mode Manual -MachineAlias laptop -RequiredCapability READ_ONLY,MAVEN `
  -EvidencePath docs\exec-plans\evidence\development-environment\calendar-w10.json
```

W11 起把 `Mode` 改為 `Automatic`，並在合併後的 CI／release verification 使用 `-RequireTracked`。
每台參與 release 的 active machine 各產生一份檔案；整合 gate 必須逐一用 `-MachineAlias` 與
`-RequiredCapability` 驗證，不能拿筆電 evidence 代替桌電，也不能用 READ_ONLY 代替實際用過的 Maven／Docker。

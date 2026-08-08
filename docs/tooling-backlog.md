# Tooling backlog

## Calendar W11 ngrok managed lifecycle

- 2026-08-08 live gate 證實 `Start-Process` 回傳新 ngrok PID 後，WMI 可能先回傳尚無
  `ExecutablePath`／`CommandLine` 的有限 snapshot。managed ownership publication 現改為短時、固定上限輪詢；
  只接受 exact executable、完整 command、registered worktree、application port 與 start-time window，並以
  process generation 防止 receipt replay。任意 PID/name、錯 command、不同 executable、未契約化 wrapper／child
  仍拒絕；receipt 不保存 webhook host 或 raw command，失敗維持 nonzero 與 startup rollback。

## Spotless wrapper 錯誤回報

- `scripts/spotless-apply.ps1` 的 catch 訊息含無效格式字串；當協調狀態寫入失敗時，原始例外會被 `FormatError` 蓋掉，且 `$exitCode` 未設定。工具專用 session 應修正錯誤格式與保證非零 exit code，並加失敗路徑 gate。

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

- Producer handoff automation v1 已有獨立 tooling implementation，但在 laptop-owned coordination PR
  發布 `TR-DESKTOP-ROUTE-BENCHMARK-START` live contract、完成 GitHub App/environment 設定、dry-run、
  真實 state-only PR／Issue receipt 與 desktop fetch/ACK 前，不得視為完成或移除此項。Tooling branch
  不得為了驗收自行修改 laptop producer state。

## 已觀察案例與決策索引

- 2026-08-08 Calendar W11 schema v4 live Docker/reuse closure：`DOCKER_TEST` 的 sandbox probe-only access
  denial 由 exact `DOCKER_SHARED_INFRASTRUCTURE_READY` host observation receipt typed supersede；receipt 僅限
  review，不授權 Docker mutation。另修復健康 runtime reuse 從 optional hashtable key 讀 generation 的
  StrictMode 例外，並強制 receipt 發布失敗回傳 nonzero。

- 2026-08-08 Calendar W11 caller-bound Automatic review closure：新增 managed operation caller
  participation contract。啟動前 sandbox probe-only access denial 可由 matching host-managed runtime／LINE
  operation receipt typed 地 supersede，保留 audit history；實際 operation participant 仍 fail closed。
  receipt 綁定 repo、Git directory、registered worktree、machine alias、capability、operation、generation、
  caller、contract、freshness 與 nonce，authority 僅限 review observation，不擴張 provider／booking／payment
  或 mutation 權限。

- 2026-08-07 Calendar W11 Automatic environment review closure：以 production-content fingerprint 取代
  DIRTY allowlist；snapshot／issue 綁 caller、capability 與 registered worktree；正式 issue lifecycle
  支援 `OPEN`、`FIXED`、`RESOLVED_BY_PROJECT_EVOLUTION`、`ACCEPTED_LIMITATION`；外部 provider 改用
  issuer、TTL、repo/worktree/caller、operation/scope 與 nonce fenced receipt。Automatic review 只要求
  本輪實際 capability。完成後由本條保留問題來源與決策索引，不列為待辦。

- 2026-08-05 route-origin hardening 在 `DOCKER_TEST` preflight 遇到 Docker CLI/context/daemon
  caller block；agent 不能以一般升權方式啟動 Docker Desktop。處置決策是：新增
  `start-managed-docker-desktop.ps1`，固定 Docker Desktop／CLI allowlist與 Docker Inc signature，使用
  `machine/docker-daemon` mutex，最多啟動一次並 bounded 等待 `docker context`、daemon info及
  `docker ps`。每次產生不含 secret 的 environment／coordination receipt；timeout、identity mismatch、
  mutex或receipt failure均 fail closed。禁止 `DockerCli -Shutdown`、Stop-Process、prune、compose down -v、
  container/image/network/volume/database cleanup。`dev-preflight`／monitor與`dev-start`只能委派此入口。

- 2026-08-05 `calendar-w11` 發生 stale Spring Maven launcher：`.dev-state.json` 仍記錄 PID 2228，
  coordination manifest 仍標 active，但 OS process／mutex 已不存在；舊 `dev-stop.ps1` 又因後續 ngrok
  verification failure 未逐 component 提交 state。處置決策是：Codex 只允許 repository-owned
  `mvn-safe.ps1`與不接受 PID 的 managed-stop wrapper；移除裸 `mvnw.cmd` allow。stop 必須同時驗證
  state／receipt、精確 worktree resource、process start time、command或 active launcher operation及
  competing owner，逐 component 原子清 state並保留 evidence。完成實作與 regression 後，此條只保留
  作案例索引，不代表尚待重做；另以 project execpolicy `forbidden` 阻擋 reset／clean／restore／stash
  類會改寫、刪除或隱藏 mixed-owner state 的 Git 操作。操作契約見 `scripts/README.md`。

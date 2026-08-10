# Tooling backlog

## Calendar W11 ngrok managed lifecycle

- 2026-08-08 PR #47 後 live retest跨過 native exact identity，但 shared coordination receipt目錄中的合法
  舊 schema沒有 `action`，replay scan在 StrictMode直接解參照而中止。managed ownership read與 replay scan
  現共用 typed schema validator：舊／非 managed evidence安全略過且保留；宣稱 managed-process-start卻缺欄位、
  型別錯誤或 JSON無效者 fail closed；只有 exact component／PID／process generation才拒絕 replay，錯誤不洩漏
  receipt內容或本機路徑。

- 2026-08-08 PR #46 後 live retest 證實此 host 的 WMI `Win32_Process` 對同 caller 永遠落入
  `LIMITED_NATIVE`，不是短暫 readiness。查詢層現將 WMI unavailable 與 process not-found 分開，並以
  Windows process handle 的 exact image path、command line、PID、creation time 作為受限替代來源。
  兩個 exact source 同時可用時必須 consensus；不一致、native access denied 或 incomplete identity
  均 fail closed。替代來源不直接授權或簽 receipt，仍由 worktree／port／command／generation／replay
  contract 驗證，且 failure evidence 不保存 webhook host、raw command 或 absolute executable path。

- 2026-08-08 live gate 證實 `Start-Process` 回傳新 ngrok PID 後，WMI 可能先回傳尚無
  `ExecutablePath`／`CommandLine` 的有限 snapshot。managed ownership publication 現改為短時、固定上限輪詢；
  只接受 exact executable、完整 command、registered worktree、application port 與 start-time window，並以
  process generation 防止 receipt replay。任意 PID/name、錯 command、不同 executable、未契約化 wrapper／child
  仍拒絕；receipt 不保存 webhook host 或 raw command，失敗維持 nonzero 與 startup rollback。

## Spotless wrapper 錯誤回報

- `scripts/spotless-apply.ps1` 的 catch 訊息含無效格式字串；當協調狀態寫入失敗時，原始例外會被 `FormatError` 蓋掉，且 `$exitCode` 未設定。工具專用 session 應修正錯誤格式與保證非零 exit code，並加失敗路徑 gate。

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

- Codex host execution policy目前會拒絕 linked worktree 的精確低風險 `git fetch origin main` escalation，
  因普通 sandbox無權寫 Git common-dir 的 `worktrees/<id>/FETCH_HEAD`，而 auto-review又禁止所有
  `require_escalated`。此項已判定由 host policy owner負責；repo-local prefix rule無法同時表達 exact argv
  termination、registered worktree／repo identity、immutable configured remote URL與 metadata-only write set，
  且不得以wrapper或間接shell繞過。最小權限contract、policy-owner prompt及allow/deny acceptance matrix見
  `docs/exec-plans/active/codex-git-fetch-execution-policy-follow-up.md`。完成host實作及Windows primary／linked
  worktree矩陣前，本項維持待處理。

- Producer handoff automation v1 已有獨立 tooling implementation，但在 laptop-owned coordination PR
  發布 `TR-DESKTOP-ROUTE-BENCHMARK-START` live contract、完成 GitHub App/environment 設定、dry-run、
  真實 state-only PR／Issue receipt 與 desktop fetch/ACK 前，不得視為完成或移除此項。Tooling branch
  不得為了驗收自行修改 laptop producer state。

## 已觀察案例與決策索引

- 2026-08-10 managed runtime false-success／orphan closure：Windows／WSL caller下的一般 `Start-Process`
  child可能在父tool返回時被job回收，而舊 `dev-start` 仍在banner或曾健康後發布成功。managed launcher現以
  Windows job breakaway＋handle allowlist啟動，只繼承worktree內的log handles；Spring Boot與ngrok都必須
  通過exact PID／start time／command／worktree／generation ownership、bounded readiness、receipt publication
  boundary及state publication後recheck，任一失敗即nonzero、revoke receipt並rollback本輪owned children/state。
  status將 `CALLER_ACCESS_DENIED`、`UNKNOWN`、`DOWN` 分開；無ACTIVE operation但exact receipt仍成立時只發
  30秒、component-scoped、single-use orphan stop authority。wrong PID/start/worktree/generation、competing owner、
  expired/replayed authority均fail closed；正常stop使用exact descendant tree，不以taskkill作成功路徑。

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

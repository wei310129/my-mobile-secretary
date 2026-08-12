# Tooling backlog

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

- Codex Desktop在大型dirty linked worktree每次工具動作前啟動`rev-parse HEAD`、`remote -v`、
  `status --porcelain`與`git config core.fsmonitor` probes；sandbox無法讀取使用者預設XDG Git ignore時，child
  `git.exe`曾累積23個並使filesystem runner在15秒`spawn_ready`前逾時。repo-local暫時以可讀的
  `.git/info/exclude`、`core.preloadIndex=true`與`core.untrackedCache=true`把371-line status降至1,115 ms，
  但host owner仍應保證probe bounded timeout、child cancellation／reaping且不隱藏dirty/untracked狀態。
- Windows sandbox filesystem helper曾在`apply_patch`回報`spawn_ready` timeout後實際完成部分multi-file寫入，
  重試因此產生重複方法。host工具應提供明確atomic outcome／operation receipt；caller在UNKNOWN outcome時可
  唯讀查核而不必盲目重試。
- 重開機後`DOCKER_TEST` preflight一度回`MATCH/ready=True`，但Docker Desktop Linux engine pipe不存在，
  Testcontainers無法建立ApplicationContext；受限`start-managed-docker-desktop.ps1`才取得daemon READY。
  preflight ready應包含當輪daemon representative command，或將「identity匹配但daemon未啟動」分型回報。

- Calendar W11 Automatic review在fresh matching managed-operation receipt存在時，會先關閉probe-only
  `DOCKER_TEST/PREFLIGHT_CALLER_ACCESS_DENIED`，隨後因同一ledger項已非OPEN而以
  `matching OPEN typed environment issue does not exist`中止；立即重跑仍可重現。review resolution應對
  同一輪重複歷史項exactly-once，且對已由相同matching evidence關閉的項目可冪等重跑；wrong caller／
  generation／capability、expired receipt與operation participant仍須fail closed，不得刪issue或停用replay求綠。

- `scripts/dev-stop.ps1` 在 `.dev-state.json` 不存在時，`Read-DevState` 回傳空物件，但 StrictMode
  仍直接讀取 `dispatcherPid`，導致停止動作前即失敗。應補向後相容的空 state schema，以及「無 state、
  僅有 coordinator-owned Spring Boot runtime」的安全停止測試。
- worktree 執行 `scripts/dev-restart.ps1 -SkipDispatcher` 時，restart 沒有轉交 `-SkipDocker`，
  可能嘗試以 worktree Compose project 重建 shared PostgreSQL／Redis。應完整轉交 typed skip policy，
  並驗證 shared containers 健康時只重啟 main runtime。
- `scripts/dev-status.ps1 -ExternalLineProbe` 的 Docker preflight 在 Docker CLI 無回應時仍缺完整的
  bounded timeout、精確 child cleanup 與 `INFRA_DOCKER_CLI_TIMEOUT` 分類；diagnostic timeout 不得
  重啟或清理 shared service。
- Windows PowerShell 5.1 的 `scripts/spotless-apply.ps1` 曾因 UTF-8 例外字串解碼與未初始化
  `$exitCode` 而錯誤回傳 0。應固定編碼、預設非零 exit code，並加入 runner 內部例外 regression。
  另需定義並測試 `-SpotlessFiles` 多檔語意；目前逗號串接會匹配 0 檔卻回成功，逐檔呼叫又因腳本
  `exit` 只執行第一檔。應接受 typed path array、逐檔驗證實際 write set，且零匹配不得回 PASS。
- stopped Docker container 的 port contract 若只讀 `NetworkSettings.Ports`，會漏掉仍保存在
  `HostConfig.PortBindings` 的 durable binding。應區分 stopped／running typed evidence，並覆蓋錯 port、
  額外 port、UDP/TCP 與零 Docker mutation。
- Docker Desktop 程序存活但 Engine 無法 ready 時，受限 launcher 目前只回 `DAEMON_TIMEOUT`。
  應在不擴大 mutation authority下提供可恢復 handoff，區分人工 restart、官方受限 restart，以及有其他
  session／container owner 時必須拒絕；禁止 shutdown、prune、compose down 或 volume cleanup。
- `scripts/tests/test-strategy-tools-test.ps1` 固定 clean-main inventory 為 387 classes，無法直接套用
  calendar-w11 的 459-class dirty tree；`environment-release-review-test.ps1` 在深層 linked worktree
  加完整 GUID state path時會超過傳統 Windows path上限。應把 clean-main baseline與 dirty-worktree
  consistency分流，fixture state改用受控短 temp root；不得由 consumer 改固定數字或刪壓力測試求綠。
- Maven/test runner尚未自動以每個testcase與suite自己的最近成功時間、P50／P95、功能增量及類型
  （unit／Spring／Testcontainers／LINE-runtime）計算有界timeout。tooling專用session應從Surefire XML建立
  Git/worktree-safe歷史，輸出可稽核的baseline、增量與餘裕；未知案例用同類provisional基準。不得用全包
  固定timeout、另一組測試時間或逾時後無限放大，且不得由Calendar產品session順手修改runner。runner還須
  分別輸出`queuedAt`、`leaseAcquiredAt`、`testStartedAt`、`testFinishedAt`、`queueWaitMs`與`executionMs`；
  execution watchdog只在實際Maven程序開始後計時。租約BUSY／caller blocked應回`NOT_STARTED`，不可污染
  testcase、suite或runner duration歷史。2026-08-10本lane兩次外層90／150秒逾時均發生於300秒租約等待，
  正式10秒租約探測回BUSY，沒有啟動Maven／Surefire，故不得列入測試時間基準。
- 2026-08-10 route pending修復期間，production runtime仍持有source-write fence時，scoped Spotless runner只等待
  而未快速回傳typed `RUNTIME_SOURCE_FENCE_ACTIVE`；外層終止後另殘留PowerShell runner。工具應在啟動Maven前
  以exact worktree/runtime owner fail fast，輸出建議的official stop入口，並對parent timeout清理自己的child；
  consumer流程則已要求第一筆production mutation前先停止同worktree runtime。
- 同輪focused測試的Surefire XML已完成18/18 PASS（最慢Testcontainers suite 81.72秒），但`mvn-safe`／coordination
  PowerShell收尾未於180秒外層上限內回傳exit。runner應區分tests-complete與lease-release／receipt-write階段，提供
  bounded finalization與可重播release；不得把完成後的runner hang算成慢test或重跑全部測試。
- sandbox 無權讀取 `C:\Users\Aiden\.config\git\ignore` 時，native Git warning會在
  `$ErrorActionPreference='Stop'` 的測試中使 checkout metadata誤判 unavailable。診斷時以隔離
  `XDG_CONFIG_HOME` 加明確 scoped `safe.directory` 可通過 20 assertions；正式工具應提供不依賴
  使用者全域 Git config的 caller-safe probe，且不得要求 consumer修改 global config。
- `stop-managed-worktree-process.ps1` 於 2026-08-08 已經精確驗證並停止 calendar-w11
  Spring Boot owner process，但呼叫在寫回 lifecycle state前逾時；OS mutex已釋放，
  `.dev-state.json` 與 operation manifest卻仍保留舊 PID／`ACTIVE`。同一 coordination root另有
  20筆相同類型的死 owner `ACTIVE` manifest。工具應使 process stop、state寫回與 operation
  release可重播且有界，並提供唯讀 reconcile／status分類；死 PID manifest不得被當成有效
  competing owner，consumer也不得手改 manifest求綠。
- 2026-08-10 Calendar W11在精確驗證port 8080唯一PID、Java identity、process start time與`.dev-state.json`
  一致後，official managed stop仍回`No exact worktree coordination resource proves ownership`；實際process與舊
  coordination receipt／manifest之間失去可驗證鏈。工具應提供同caller、同worktree的唯讀reconcile，只有exact
  PID／start time／command／generation與無競爭owner全部一致時補發bounded stop authority；任一不符仍fail closed。
- 同輪sandbox caller的`dev-status.ps1`因Docker／process identity access denied誤報Spring Boot、PostgreSQL、Redis、
  ngrok全數停止，但host managed caller與actuator證明服務健康。status輸出應明確區分`CALLER_ACCESS_DENIED`與
  `DOWN`，不得把無法觀察渲染成服務停止；release仍只接受matching managed receipt與host-caller官方結果。

## 已觀察案例與決策索引

- 2026-08-09 Calendar W11 historical managed-evidence renewal：PR #49已讓同一current receipt的重跑
  exactly-once，但live ledger另有已FIXED的DEV_RUNTIME／LINE_E2E probe-only issue，其resolution evidence
  綁舊contract、舊runtime generation與舊snapshot。Automatic review在current contract下正確將其視為OPEN，
  且能找到fresh current managed receipt；resolver卻因persisted status已FIXED而拒絕更新。工具需要獨立驗證
  historical managed evidence的typed identity、結構與`REVIEW_OBSERVATION_ONLY` authority，然後只在current
  receipt完整匹配repo／worktree／machine／caller／capability／operation／generation／contract／freshness／
  snapshot時原子renew evidence並保留lineage。同一current receipt重跑需idempotent；tamper、wrong scope、
  expired、非核准code或operation participant一律fail closed。回歸必須涵蓋old contract＋old generation
  FIXED → current contract＋new generation renewal → immediate rerun，並在同輪繼續處理真正OPEN的Docker項。
  已由PR #50（merge `0f86167f7cc9675e507a03e1933351472441d3c5`）完成；Calendar live retest以
  focused 64 assertions及六項schema v4 Automatic `PASS/openCount 0`驗證，該項關閉。

- 2026-08-08 Calendar W11 mixed-receipt closure：PR #47後live retest跨過native exact identity，但shared
  coordination receipt目錄中的合法舊schema沒有`action`，replay scan在StrictMode直接解參照而中止。
  managed ownership read與replay scan現共用typed schema validator：舊／non-managed evidence安全略過且保留；
  宣稱`managed-process-start`卻缺欄位、型別錯誤或JSON無效者fail closed；只有exact component／PID／
  process generation才拒絕replay，錯誤不洩漏receipt內容或本機路徑。

- 2026-08-08 Calendar W11 native exact process closure：PR #46後live retest證實此host的WMI
  `Win32_Process`對同caller永遠落入`LIMITED_NATIVE`，不是短暫readiness。查詢層將WMI unavailable與
  process not-found分開，並以Windows process handle的exact image path、command line、PID與creation time
  作為受限替代來源。兩個exact source同時可用時必須consensus；不一致、native access denied或incomplete
  identity均fail closed。替代來源不直接授權或簽receipt，仍由worktree／port／command／generation／replay
  contract驗證，failure evidence不保存webhook host、raw command或absolute executable path。

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

- 2026-08-05 route-origin hardening 在 `DOCKER_TEST` preflight遇到 Docker CLI/context/daemon caller
  block。正式處置是 repository-owned managed Docker Desktop入口驗證固定 identity、mutex與 bounded
  readiness；timeout、identity mismatch或 receipt failure均 fail closed。
- 2026-08-05 calendar-w11 曾有 stale Spring Maven launcher。只允許 `mvn-safe.ps1` 與不接受任意 PID
  的 managed-stop wrapper，並以 state／receipt、worktree、process start time與 competing owner驗證。
- PR #26（`0eba57aab203b8cd861055b4105d2a31b37464ec`）已修復固定 LINE host／port validator與 linked-worktree
  build identity；對應 lifecycle 13 assertions、Maven identity 8 assertions曾通過。
- Producer-handoff automation已由 main發布；calendar-w11 已整合 engine、policy、兩個 workflow與測試。
  本 lane驗證 engine 18 assertions、workflow contract 22 assertions通過，外部 mutation為 0。

## 2026-08-07 PR #38 resolution receipt

- `origin/main@a45fe8496259baa27f2085db865d0597f50a58b3`（PR #38）已提供並在 calendar-w11整合：
  dirty production-content fingerprint與 linked-worktree identity、capability-scoped Automatic review、
  typed issue lifecycle（`FIXED`／`RESOLVED_BY_PROJECT_EVOLUTION`／`ACCEPTED_LIMITATION`）、
  `dev-environment-report.ps1`、短效 scoped external-authority receipt，以及不含 machine-local absolute
  path的 tracked evidence。
- 本 lane已通過 authority 27、report 12、preflight 32、短路徑 release-review 11、
  sandbox-safe service-version 20與 worktree-review 13 assertions。舊的 dirty-runtime fingerprint、
  caller snapshot、任意 external receipt、缺少 report入口與 absolute evidence問題不再列為待處理。
- 舊 Automatic review與所有舊 runtime／Docker／LINE receipts仍不得作 release evidence；必須在整合後以
  本 lane實際使用的 capabilities重新取得 fresh receipts與 Automatic review。

## 2026-08-10 Automatic review evidence-path usability

- `dev-environment-review.ps1 -EvidencePath`在 approved evidence root存在時仍把 repository-relative完整路徑
  再次接到root後方，產生重複的`docs/exec-plans/evidence/development-environment/...`巢狀路徑；改傳單一檔名
  才寫入預期位置。Calendar session已刪除本次誤產生且可重建的重複evidence，正式evidence與assertion均PASS。
- tooling專用session應讓參數契約與文件範例一致：接受approved root下的repository-relative完整路徑，或在
  help／錯誤訊息明確要求root-relative檔名；同時加入拒絕重複root片段的failure-first測試。一般產品session
  不修改review腳本。

## 2026-08-10 managed stop self-owner classification

- calendar-w11在單一session、registry於指令外零ACTIVE operation時，`stop-managed-worktree-process.ps1`
  與上層`dev-stop.ps1`仍一致拒絕Spring Boot stop：`Another active owner on the exact resource cannot be
  classified.`。每次失敗後registry再次為零ACTIVE，證據指向stop指令自身operation在native process query
  下被當成競爭owner，而非其他session。
- tooling專用session需建立failure-first：同一caller持有合法lifecycle operation並停止其已驗證managed
  Spring PID時，self owner應被typed辨識且不視為競爭；不同PID／不同start time／不同worktree仍fail closed。
  修復前產品session不得taskkill或修改lifecycle腳本。

## 2026-08-10 managed runtime false-success and sandbox persistence

- Calendar W11在使用者已確認無其他session、shared registry零ACTIVE operation後，舊generation process tree仍
  引用本worktree，但official stop因unclassified owner拒絕。依使用者既有精確授權終止`.dev-state.json`所指
  PID 16204 tree後，official stop才回`PROVEN_NOT_RUNNING`並完成state release；工具仍需提供typed orphan
  reconcile，避免consumer被迫在「永遠無法官方停止」與「繞過ownership」間選擇。
- 後續official `dev-start.ps1 -SkipDispatcher`以host協作鎖執行並exit 0、寫入generation
  `befb5988345245d9ac5b71518d2b579a`；log已進Spring Boot banner且err為空，但caller返回後Spring Boot、
  PostgreSQL、Redis、ngrok均不可見，official ExternalLineProbe為disconnected。launcher不得在child將被
  sandbox/job回收、durable process ownership未建立或bounded post-return readiness未通過時回成功；status也不得
  把caller無法觀察與DOWN混為一談。已交由獨立tooling producer以failure-first與Windows／WSL caller矩陣修復。
- 已由PR #52（head `112d838ee02d1f4d7a692acf8a4aecfcea9ea097`、merge
  `390609509ebd961a2fc57a960927b4fbb27afffd`）完成typed closure：managed launcher使用Windows job
  breakaway與handle allowlist；Spring Boot／ngrok在receipt與state發布後重驗exact process及bounded readiness，
  失效即revoke／rollback並nonzero。status分離access denied／unknown／down；exact orphan只取得30秒、
  component-scoped、single-use stop authority，wrong PID／start／worktree／generation、competing owner、expired
  或replay均fail closed。Calendar仍須以本機fresh receipts重跑official live gate後才能關閉consumer blocker。
## 2026-08-10 managed process fingerprint start-time precision mismatch

- PR #52 merge `390609509ebd961a2fc57a960927b4fbb27afffd` 已在 Calendar W11 exact 同步；直接相關
  managed-runtime、caller-classification、process-lifecycle、dev-start receipt 與 ngrok lifecycle 共 108 assertions
  PASS，fresh `dev-start.ps1 -SkipDispatcher` 亦成功回傳。
- Post-return host `dev-status.ps1 -ExternalLineProbe` 證實 Spring Boot health `UP`、PostgreSQL／Redis healthy、
  LINE 官方 E2E connected，但同一 Spring Boot PID 被錯判為
  `ORPHAN_UNVERIFIABLE/COMMAND_FINGERPRINT_MISMATCH`。安全雜湊診斷顯示 receipt 的
  `processStartedAt` 為 `2026-08-10T14:44:38.4765575Z`，後續 WMI＋native consensus snapshot 為
  `2026-08-10T14:44:38.4765570Z`，僅差 500ns；PID、executable、command contract與generation均相同。
- 根因是 process identity consensus 已容許不同來源的 start time 在一秒內一致，receipt start-time gate亦容許
  一秒內差異，但 `Get-ManagedProcessCommandFingerprint` 仍把未正規化的七位小數原始時間直接納入
  fingerprint，造成 Windows WMI 與 native FILETIME 精度／轉換差異把同一程序判成不同 identity。
- Tooling producer應建立failure-first Windows fixture，涵蓋啟動時native-only、後續WMI＋native consensus
  以及500ns／亞微秒精度差異；以單一明確、保守的UTC canonical precision產生fingerprint，同時維持
  PID、executable、command、worktree、component、generation與receipt replay的fail-closed fences。
  修復後須驗證跨caller post-return status為READY、真正不同start time仍拒絕、stop/restart exactly-once，
  並通過focused與完整official tooling suite。不得把容許窗擴大成模糊process identity，也不得觸碰
  Calendar W11 dirty worktree或產品碼。
- 在Git-verifiable tooling修復合併並exact同步前，Calendar runtime／LINE gate不得標PASS，新的24h／20筆
  baseline不得啟動；目前LINE connected僅是診斷事實，不是release evidence。
- 已由PR #53（head `b17589bed5aa045e4c4db712496166cf2d9f6c34`、merge
  `6657af5ca1c76de299c4ab517fb748be844d6ed4`）完成：creation time統一為UTC微秒向下截斷，v2 receipt
  明載`UTC_MICROSECOND_TRUNCATED`，v1僅在完整typed identity與canonical time相符時窄幅相容。
  Failure-first重現500ns誤判；focused 4 suites／84 assertions、neighbors 5 suites／81 assertions、official
  tooling 29 suites／506 assertions及PR required checks全數PASS；external product／Calendar／Docker／LINE
  mutation 0。Calendar仍須在本機重跑focused與post-return runtime gate後才可關閉此release blocker。

## 2026-08-11 stale coordination operation and owner PID reuse

- PR #53 exact同步後，本機fingerprint focused／lifecycle neighbors 5組共110 assertions PASS；既有v1 runtime
  不再回`COMMAND_FINGERPRINT_MISMATCH`，Spring Boot health UP、PostgreSQL／Redis healthy、LINE官方E2E
  connected。下一個獨立blocker為`ORPHAN_BLOCKED_COMPETING_OWNER/ACTIVE_COMPETING_OWNER`。
- 精確Spring Boot Maven resource仍有多筆歷史operation manifest標成`ACTIVE`；除目前真正owner PID
  `456992`外，多數owner PID已不存在。另有2026-08-02建立的operation宣稱owner PID `7840`，但目前PID
  `7840`是2026-08-10才啟動的`dasHost.exe`，證實Windows PID reuse。raw operation scanner只依status與PID
  數值判斷，未重驗owner process creation identity，也未排除已證明死亡的舊ACTIVE manifest，因而製造假競爭。
- Tooling producer須failure-first覆蓋dead owner、PID reuse、current exact owner與mixed legacy manifests；新operation
  publication應保存typed canonical owner process start identity，scanner／status／orphan stop必須共用typed
  validation。dead owner及可證明PID reuse應分類為ABANDONED而不阻擋；query access denied／identity incomplete
  仍fail closed；不得依名稱猜測、不得手動刪receipt／operation、不得放寬成「PID存在即owner」。legacy manifest
  只能在process start早於operation publication且其他typed resource／worktree證據一致時窄幅接受。
- 修復須驗證read-only status不做隱性mutation、正式reconcile/stop的exactly-once與retained evidence、restart新owner、
  competing live owner仍阻擋、PID reuse不誤擋，並跑focused、coordination/lifecycle neighbors與完整official tooling
  suite。合併並在Calendar本機重驗前，runtime／Automatic／baseline不得標PASS。
- Calendar consumer已用目前真實typed runtime state與純記憶體operation fixtures執行四案failure-first：
  `current-only`正確為`MANAGED_ACTIVE`；`live-competitor-plus-current`正確阻擋；但
  `dead-plus-current`與`pid-reuse-plus-current`均錯回`ORPHAN_BLOCKED_COMPETING_OWNER`。fixture未寫檔、
  未停止程序、external mutation 0。Producer修復後必須用同一行為矩陣轉綠；consumer仍須另跑official
  stop→DOWN→start→caller-return→新caller READY→LINE E2E→stop/restart實測循環，controlled fixture不得取代live gate。
- 已由PR #54（head `9693cf19c0e24e0c9f649b5632c2ed37f295f45b`、merge
  `862aa45b3d83dea1341c70a78aa57574b1dc49b3`）完成：新operation保存typed canonical owner identity，
  shared validator區分dead、PID reuse、current exact owner與genuine live competitor；read-only只分類，正式
  reconcile才原子保留`ABANDONED` evidence且exactly-once。Failure-first四案與修正後focused 18 assertions、
  coordination/lifecycle neighbors 9 suites／161 assertions、official tooling 30 suites／527 assertions及PR CI
  全數PASS；external product／Calendar／Docker／LINE mutation 0。Calendar仍須完成本機controlled與live cycle。
- Calendar本機同步後，正式owner-identity／coordination／fingerprint／lifecycle／durability五組105 assertions
  PASS；四案矩陣全綠。live cycle的cold `dev-start -SkipDispatcher`亦於111秒成功，fresh generation
  `bd54127d129f4682a8111a2fe399acc8`、Spring Boot UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE
  official E2E connected。但跨caller status被一份合法歷史named-ID RELEASED manifest觸發
  `COORDINATION_EVIDENCE_INVALID`，證實controlled fixture未涵蓋真實legacy ID contract。
- Registry aggregate顯示安全named、filename與operationId相符的legacy schema v1 evidence共有168份：ACTIVE 9、
  RELEASED 159；GUID形式另有ACTIVE 34、RELEASED 2614。新validator在status/resource篩選前對所有evidence
  強制GUID，造成out-of-scope terminal legacy evidence阻擋current owner。相容不得接受path separator、`..`、
  filename mismatch或任意字串；new typed publication仍應用GUID，legacy只接受bounded safe ID與既有typed envelope。
- live `dev-stop`另揭露mutation ordering：程序停止後才執行stale reconcile；reconcile遇上述legacy validation失敗時，
  命令回nonzero且state保留，但Spring/ngrok已DOWN。下一修復須failure-first證明所有stale/evidence preflight在
  StopAdapter呼叫前完成；preflight失敗時stop invocation=0。若preflight成功，需在mutation boundary重驗exact
  current owner，之後才stop並原子完成state／receipt；不得以刪除歷史evidence或放寬invalid JSON解決。
- 已由PR #55（head `cac4c23fb8367622e91c40015bfc6d7856c2ebcf`、merge
  `c514f31f221ff7a841511dd9e2ed380f453de8c4`）完成：safe named legacy ID採bounded path-safe validation且
  filename必須吻合；new publication仍為GUID。stop在mutation前完成stale preflight與current owner revalidation，
  並以deterministic recovery contract支援completion中斷後不重複stop的官方replay。Failure-first兩案、focused
  32 assertions、neighbors 9 suites／161 assertions、official tooling 31 suites／559 assertions及PR CI全數PASS；
  external product／Calendar／Docker／LINE mutation 0。Calendar仍須以真實歷史registry重跑live cycle。
- Calendar真實歷史registry live cycle已於2026-08-11完成：第一輪official `dev-stop`在Spring停止驗證逾時後
  保留ownership，第二輪replay無重複停止Spring並繼續處理ngrok；ngrok停止驗證逾時後同樣保留ownership，
  第三輪replay於6.2秒完成收斂。兩次驗證逾時後唯讀查詢均確認對應PID已不存在；未手動清state、未直接kill。
  隨後fresh `DEV_RUNTIME`／`LINE_E2E` receipts皆MATCH，official `dev-start -SkipDispatcher`於66.1秒成功，
  新generation為`3670046f21d94e3fad241ff2b463ae19`；跨caller `dev-status -ExternalLineProbe`於25秒PASS，
  Spring為VERIFIED_DIRTY且官方LINE→ngrok→Spring Boot連通。剩餘觀察：健康runtime的ngrok仍顯示
  `ORPHAN_EXACT_RECONCILABLE`；另正常停止若持續需要replay，應以本次實測15秒驗證窗與實際停止延遲為基準，
  另立failure-first tooling工作，不得任意猜測或放寬timeout。
- 2026-08-11 Route→Place功能session觀察到兩個正式入口錯誤處理缺口：`stop-managed-worktree-process.ps1`
  讀取未初始化的`$script:DevVerboseOutput`；`spotless-apply.ps1`由Windows PowerShell直接載入無BOM UTF-8內容時，
  catch訊息中的格式字串被錯誤解碼，接著讀取未初始化`$exitCode`，造成表面exit 0但未執行Spotless。本輪未修改
  tooling；以相同腳本內容、明確UTF-8讀取、正式協作鎖與SOURCE_WRITE fence完成Spotless。tooling producer應建立
  failure-first，驗證PowerShell 5.1直接`-File`執行時成功與失敗均回正確exit code，且不降低協作鎖或source fence。

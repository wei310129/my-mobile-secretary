# 開發 Session 自協調 Pipeline 與殘留治理計畫（Terra High 執行版）

> 狀態：全部 Phase gate 已完成；所有 destructive cleanup 仍維持 disabled/fail-closed
> 更新日期：2026-07-22
> Track：開發基礎設施／工具專用
> 執行原則：每階段通過 hard gate 後才進入下一階段；持久資料與不確定狀態一律 fail closed
> 啟動提示詞：[development-session-coordination-terra-high-prompt.md](development-session-coordination-terra-high-prompt.md)

## 0. 目的與使用方式

本計畫要把目前分散的啟停、測試、Docker、Maven、長駐程序與多 agent 開發操作，收斂成可由
agent 自行協調的 pipeline。完成後，執行者不需要憑經驗猜測「現在能不能重啟、是否有人正在測試、
上次失敗留下了什麼」，而能先宣告資源、取得正確租約、執行、驗證、清理自己的暫態物件，最後留下
下一個 session 可機器判讀的 handoff receipt。

本文件是詳細執行契約；長期架構邊界見 docs/architecture.md 第 36 節。若內容衝突，依序採用：

1. 使用者當輪明確決策與根 AGENTS.md。
2. docs/decisions/current.md 的已批准決策。
3. docs/architecture.md 的架構不變量。
4. 本計畫的已通過 gate；未通過項仍是建議方案，不冒充已落地能力。

本計畫不授權自動刪除資料庫 volume、Flyway history、使用者來源變更、Git branch、secrets 或
Dispatcher 的不確定 active run。任何 destructive cleanup 都必須另有明確批准、精確目標與驗證。

### 0.1 Terra High 執行契約

- 唯一主計畫就是本文件；不得另起平行 plan、只重寫計畫，或在完成 Phase 0 後停下等待一般性確認。
- 從 Phase 0 開始，先完成文件／prototype gate；通過後直接實作 Phase 1，之後依序持續到下一個真正
  blocker、需要新權限／destructive approval，或全部 Phase 完成。
- 第 1.2 節是本 track 的起跑預設。若 repository evidence 證明某項在 Windows／sandbox 不可行，
  Terra 可在 Phase 0 選擇等價且更安全的機制，記錄證據、相容性與 fallback；不需為同義實作細節停下詢問。
- 每個 Phase 開始前更新本文件的 current phase、預計檔案與驗證；gate 結束後回填實際命令、結果、
  skipped paths、residual、風險與下一步。
- 實作期間保留 unrelated dirty worktree；不得以 reset、checkout、stash、clean 或格式化整個 repository
  消除他人變更。所有 root Maven lifecycle 依根 AGENTS.md 經 mvn-safe 執行。
- Phase 1 前不得碰 live Docker；Phase 3 前不得切換 live lifecycle。Live／destructive gate 仍需本文件
  指定的額外批准，不能以「繼續到完成」擴張授權。
- 第 11 節只提供壓縮候選點；實際時機依根 `AGENTS.md` 由當下 agent 綜合開發品質、開發效率與 token 效率自主判斷，必要時可提前、延後、跳過或新增候選點。

目前執行入口：

- Current phase：完成（Phase 5 Rollout、文件與可靠度回饋 gate 已通過）。
- Phase 0 出口：resource schema、lock backend／visibility、compatibility／total order、cleanup classes、
  fake adapters 與 validation commands 全部有可驗證結果。
- Phase 0 後路徑：Phase 1 至 4 gate 已有隔離驗證；本輪完成 Phase 5 release gate 回填。

### 0.2 本次執行：Phase 5（release gate）

- 預計修改：repository-owned Maven、Spotless、dev lifecycle、doctor、handoff/ledger 與本計畫／操作文件；
  Dispatcher 維持獨立 contract，主產品 Java 與主 Flyway 不變。
- 限縮檢查：`scripts/`、`internal/ai-dispatcher/` 的已建立 drain contract、協調文件；不掃描產品 domain。
- 本輪 validation：所有 fake/disposable gate、PowerShell parse、coordinator-aware root `test-compile` 與 read-only
  doctor。Docker、Compose、LINE 與 destructive cleanup 不作 failure injection。
- live paths：既有 Dispatcher drain/resume smoke 已完成；本輪不做 Docker/volume/Flyway repair 或真實 Codex run，
  因此所有 persistent/shared resource 均 retained。

## 1. 使用者要求與 v1 執行基線

### 1.1 已確認成果

- 盤點不只限於重啟與測試，涵蓋所有可能跨 session 競爭或留下下一輪影響的開發資源。
- 明確定義哪些操作可並行、哪些必須互斥、哪些應以 session／worktree 隔離。
- 多 agent 能查詢 owner、排隊、接手已確認 abandoned 的操作，而不是互相覆寫或猜測。
- 正常完成、取消與失敗都要產生 handoff；可安全判定為本 session 擁有的暫態物件才自動清理。
- 失敗率高、步驟多、常被誤用或診斷成本高的操作優先腳本化。
- 協調模型、清理邊界與 Dispatcher 關係需同步進架構文件。

### 1.2 Terra High 起跑預設

以下預設已選為本 track 的起跑方向。Phase 0 負責驗證並 freeze 具體 backend／schema，而不是重新詢問
是否需要協調 pipeline：

1. 採「一組共享互動環境＋隔離測試 lane」：
   - 主 Spring Boot、主 Compose、ngrok／LINE 與 Dispatcher runtime 是單機共享 singleton。
   - 一般 session 以 shared consumer lease 使用符合預期 generation／設定的健康服務；只有 coordinator
     的 exclusive transition 可改變其生命週期。
   - 一般 integration test 使用 Testcontainers 與隨機 port；三個 opt-in local live evaluation 另走
     shared-live-evaluation lease，不得冒充隔離測試。Docker 重型測試先以容量 1 為可靠度基線。
2. 一個 writable agent 對應一個 worktree；同一 worktree 同時最多一個無外部協調的 source writer。
   同一主 agent 內的 sub-agent 若共用 worktree，必須由主 agent分派互不重疊的檔案範圍。
3. 同一 worktree 的根 Maven target 與 Dispatcher target 各自單一 writer；兩者只有在 target、DB
   與 Docker capacity 都隔離時才可並行。
4. 主／Dispatcher dev PostgreSQL／Redis volume、Flyway history、Dispatcher lane 與 Git 內容預設保留；
   自動清理只處理 owner 可證明、建立時已標記 disposable 的測試暫態資源與專用測試 volume。
5. ordinary start 可以等待 Docker daemon；會 shutdown／kill Docker Desktop backend 的 recovery
   必須是 machine-global exclusive，且有其他 Docker consumer 時拒絕執行。
6. 保留 shared main／Dispatcher Compose 的既有 project／volume identity；failure injection 另建無固定
   container name、隨機／配置 port、明確 disposable volume 的 fixture。
7. Coordinator 對 repository-owned wrappers 提供 correctness guarantee；IDE／direct mvnw／docker
   視為 unmanaged bypass，先偵測並 fail closed，不宣稱可攔截所有外部程序。
8. Machine-global 與 repo-common registry 分層；確切 Windows path／lock primitive 由 Phase 0 prototype
   依跨 process、worktree、clone、sandbox visibility 證據選定。

## 2. 現況證據與主要缺口

本輪只做靜態稽核，未執行 Docker、Maven、啟停或 destructive command。

1. scripts/mvn-safe.ps1 是唯一現有互斥入口，且只保護經它進入的根 Maven lifecycle。
   固定的 Local named mutex 不涵蓋其他 Windows session、直接 mvnw、IDE、dev-start 或 Dispatcher。
2. scripts/dev-start.ps1 直接以兩個 Maven spring-boot:run 啟動主程式與 Dispatcher，因此長駐程序與
   測試／clean 可同時寫入相同 target。這是目前重啟與測試競態的最高可信來源之一。
3. start、stop、restart 沒有包住完整 transition 的 umbrella lock。兩個 start 可同時通過 port
   preflight；full restart 的 stop 與 start 之間也可被另一 session 插入。
4. scripts/.dev-state.json 是未上鎖的 read-merge-write，且不是 atomic replace；固定 log 檔也沒有
   run owner。並行或程序中止可能造成 lost update、無效 JSON、錯誤 PID 與跨輪 log 誤歸因。
5. stop 在 taskkill 後未驗證 exit code、process tree 與 port 已消失，就先清空 state 並可能宣告成功。
6. Ensure-DockerDaemon 逾時後會 shutdown 並強制停止 Docker Desktop backend。此操作影響整台主機，
   目前卻沒有 global lock，也不知道是否另有 Compose／Testcontainers consumer。
7. 根 compose.yaml 使用固定 container name、5432／6379、project-scoped persistent volumes；Dispatcher
   另有固定 5433。只改 Compose project name 無法消除 container name 與 host port 衝突。
8. 一般 root integration test 已透過 Testcontainers 建立獨立 PostGIS／Redis，資料不會與本機 Compose
   共用；但多個 Maven process 仍會競爭同一 target、Surefire report 與 Docker capacity。同一 Maven
   JVM 的測試共用 ApplicationContext／DB／Redis，且每個方法前執行全庫 reset SQL，因此在未做到
   per-test DB/schema 隔離前不得啟用 JUnit class／method parallel。
9. 三個 opt-in ScheduleIntent live evaluation 使用 local profile 的完整 SpringBootTest，未匯入
   Testcontainers；啟用時可能連到 shared Compose 並執行 Flyway，必須另列 shared-live-evaluation lane。
10. Dispatcher 的 PostgreSQL lane、fencing 與 recovery 只保證它自己最多一個 active run。既有文件已
   明說它不能阻止人類或其他 Codex process 修改同一 worktree，不能把它誤當全 repo coordinator。
11. Flyway migration 編號、Spotless apply、Git index／commit 與共享 worktree 編輯也是跨 agent 競態，
    即使 Docker 完全正常仍可能在合併或驗收時才顯現。

## 3. Resource concurrency matrix

| Resource key | Scope／mode | 現況與 v1 協調政策 | 完成、清理與 handoff |
| --- | --- | --- | --- |
| machine/docker-daemon:{daemonId} | machine-global RW | 所有 Docker client 持 shared；Desktop shutdown／kill repair 持 exclusive。Context 不等於 daemon identity | daemon 不可查且 consumer 狀態不明時 BLOCKED；禁止 prune |
| machine/docker-capacity:{daemonId} | machine-global counting | 在 daemon shared lease 內取得；重型 Testcontainers v1 容量 1 | 只按自訂 operation／generation label reconcile，不碰 Ryuk 或通用 bridge network |
| machine/port:{host}:{port} | machine-global exclusive mutation | 5432／5433／6379／8080／8091／4040 都跨 clone 競爭 | PID、start time、command、port、generation 一致才可停 |
| machine/ngrok-runtime:{host}:4040 | machine-global RW | tunnel mutation exclusive；相關 consumer shared | 只停本 operation 建立且 ownership/fencing token 一致的 tunnel |
| machine/line-webhook:{channel} | external singleton RW | 設定／probe correlation 序列化；不得把另一輪 request 當成功 | receipt 不保存 LINE credential，只存非秘密 correlation |
| repo/git-common:{repoId} | repo-common exclusive mutation | worktree create/remove、shared refs/config/gc 需協調 | 不自動刪 worktree、branch、stash |
| repo/flyway-sequence:{app} | repo-common exclusive reservation | 同 clone/worktree reserve；跨機器仍靠 merge/CI uniqueness gate | 不自動 rename 已發布 migration；衝突先停 |
| worktree/source:{worktree} | worktree exclusive writer | 多 agent 可互蓋；獨立 worktree 優先，path claim 只作輔助 | 不自動回復來源；回報 dirty paths 與 owner |
| worktree/git-index:{worktree} | worktree exclusive mutation | add／commit／rebase 可混入他人變更 | 提交前重驗 claim/status；衝突標 BLOCKED |
| worktree/maven-target:{app}:{worktree} | worktree exclusive writer | root 與 Dispatcher 各自 target；direct mvnw／IDE 可能 bypass | target suspect 才允許 lease 內 clean；unmanaged writer fail closed |
| environment:{environmentId} | machine-global RW | LINE E2E／shared runtime 使用者持 shared consumer；start／stop／restart 持 exclusive transition | transition 等 consumers drain；status 取得 shared 或回 STALE/BUSY |
| compose:{daemonId}:{project} | machine-global exclusive mutation | main／Dispatcher fixed names、ports、volumes；只改 project name 不足 | shared dev volume retained；disposable fixture 必須無固定名稱／port |
| data:{instance}:{databaseOrNamespace} | machine-global RW | runtime／live evaluation consumer shared；migrate/reset exclusive | Flyway 失敗 quarantine；禁止 clean／repair／FLUSHALL |
| service-state:{environmentId}:{generation} | atomic CAS | 目前 state 非 atomic 且 request defaults 可覆寫 reused resource 真相 | 保存實際 artifact/config/profile/mode；不以本輪未觸及值清空 |
| service-log:{environmentId}:{generation} | generation-owned | 固定 log 會覆寫；長駐服務 log 超過 transition 壽命 | retention 不碰仍 active 的 service generation |

Canonical total order 以 rank、resource type、正規化 key 排序，不使用 caller 傳入順序：

1. rank 10：machine/docker-daemon。
2. rank 20：machine/docker-capacity。
3. rank 30：machine/port、machine/ngrok-runtime、machine/line-webhook。
4. rank 40：repo/git-common、repo/flyway-sequence。
5. rank 50：worktree/source、worktree/git-index、worktree/maven-target。
6. rank 60：environment。
7. rank 70：compose。
8. rank 80：data。
9. rank 90：service-state、service-log。

同 rank 先依上列 resource type 次序，再依 ordinal key 排序。Shared 可與 shared 相容；exclusive 與任何
其他 holder 衝突；counting 受容量限制且必須同時持有 daemon shared。禁止 lock upgrade；需要改 mode 時
結束舊 operation、釋放後以新宣告重進。任何 acquire timeout、cancel 或例外都必須按反向順序釋放已取得
的部分 locks。狀態查詢須取得 environment shared 以讀同一 generation，否則明確回 STALE／BUSY。
會觸發 LINE 官方 webhook 的 probe 另取 external key，不能偽裝成純 read-only。

## 4. 協調平面設計

### 4.1 邊界

- Coordinator 屬 repository development tooling，不進主 Spring application dependency graph，不使用主產品
  DB／Redis 作為 bootstrap lock，也不改產品 runtime 的健康語意。
- Dispatcher 繼續擁有自己的 session、lane、run、heartbeat 與 fencing。Coordinator 目前只能透過既有
  唯讀 operator 狀態做 fail-closed 檢查；durable pause／drain contract 需於 Phase 3 新增或驗證。契約
  不存在時 stop／restart 必須 BLOCKED，不得直接清 DB row、釋放 claimed event 或把 unknown 當 stale。
- Coordinator 在 Docker 未啟動時仍要可用，因此 correctness primitive 不可依賴 Compose DB。
- 鎖與 receipt 不得保存 secrets、token、原始 LINE 訊息、個資或完整命令環境。

### 4.2 Lock、lease 與 fencing

建議 v1 使用「OS 持有中的 exclusive lock＋machine-local registry metadata」：

1. OS lock 是同機 correctness authority；registry JSON 只提供 owner、等待與 handoff 可觀測性。
2. registry 分 machine-global 與 repo-common scope。前者必須跨 clone／Docker context 看見相同 daemon、
   ports、runtime 與 LINE owner；後者由 Git common-dir 或經驗證的等價位置讓同 clone 的 worktree 共用。
   root 可注入供測試；Phase 1 需驗證一般 PowerShell、Codex sandbox、不同 worktree、不同 clone與可行的
   Windows logon boundary。不同機器的 Flyway reservation 仍由 merge/CI uniqueness gate 收斂。
3. 每次 acquire 產生 operation ID 與單調 generation。後續 stop、cleanup、callback 都帶 token；
   舊 operation 不可清理或 settle 新 owner。
4. wrapper 在 child command 全生命週期持有 operation lock；程序 crash 時 OS 釋放 lock，但殘留
   manifest 先標 abandoned，下一輪需 reconcile 才能接手。
5. heartbeat／TTL 只能觸發稽核，不可單憑逾時強搶。至少重驗 owner PID、process start time、command
   identity、port owner、Docker labels 與 Dispatcher durable state。
6. 長駐共享服務在 READY 後釋放 transition lock，但留下帶 generation 的 service ownership record；
   至少保存 artifact commit/dirty digest、immutable artifact digest、profile、Dispatcher ARMED/DISARMED、
   ngrok/LINE mode、非秘密 configuration digest、Flyway version與 process identity。下一次 consumer／
   mutation 必須重驗 record 與真實世界一致；reused／untouched resource 不可被本輪 request defaults 覆寫。

Phase 1 必須以 fake adapters 驗證 lock backend；在 backend 尚未通過跨 process 測試前，不整合 live Docker。

### 4.3 Pipeline 狀態機

每個可變更環境的命令固定走：

RESERVE → PREFLIGHT → RECONCILE → EXECUTE → VERIFY → COMMIT_RECEIPT → RELEASE

- RESERVE：解析 session、worktree、operation 與完整 resource set，按固定順序取得。
- PREFLIGHT：驗證工具、權限、dirty policy、ports、Docker context、Dispatcher lane 與 destructive boundary。
- RECONCILE：比對上一輪 manifest、PID、ports、containers、volumes、target marker；未知狀態先 quarantine。
- EXECUTE：呼叫 adapter；外部程序不得在 registry metadata write lock 中執行。
- VERIFY：以獨立證據確認結果，不能只相信命令 exit 0。
- COMMIT_RECEIPT：atomic 寫入 terminal outcome、resource disposition、測試／健康結果與下一步。
- RELEASE：釋放 operation locks；需要保留的共享服務轉成明確 stable ownership，不是假裝已清理。

建議穩定 outcome：

- READY：所有必要 gate 通過，無未解釋 residual。
- DEGRADED：主要目標可用，optional component 失敗且 receipt 已明列。
- BUSY：租約被合法 owner 持有，未改任何資源。
- RECOVERY_REQUIRED：operation 中止或驗證失敗，存在 owner 明確但尚未處置的 residual。
- BLOCKED：owner／outcome 不確定、Dispatcher active run、Flyway failure 或 destructive approval 缺失。

### 4.4 Session receipt 與 handoff contract

每輪不論成功、失敗或取消都產生不含秘密的 machine-readable receipt，至少包含：

- schema version、operation／session／agent ID、worktree、branch、PID 與時間。
- 宣告、取得、等待與釋放的 resource keys；每個 resource 的 owner generation。
- Compose project／Docker context／container labels／ports；不保存 DB 密碼。
- process identity、created／reused／untouched、artifact與非秘密 configuration digest、health、exit code
  與 bounded diagnostic path。
- Flyway version、測試摘要、target suspect marker。
- disposition：removed、kept-shared、kept-persistent、quarantined、unknown。
- 未完成 cleanup、下一 session 必做 recovery、blocked reason 與是否需要使用者批准。

session end gate 只有在沒有自己的 active mutation lease，且每個 residual 都有明確 disposition 時才可標
HANDOFF_READY。Git dirty 本身不等於失敗，但必須歸屬明確，且不可被 cleanup 還原或刪除。

## 5. 高失敗率操作的腳本化策略

### 5.1 排序方法

Coordinator 上線後，每個 operation 記錄穩定 failure code、失敗 phase、耗時、重試、residual 與人工介入。
每週或每 N 次 operation 依下列因子排序：

- 發生頻率與最近趨勢。
- blast radius：是否會中斷其他 session、毀損 persistent data 或造成錯誤成功。
- 手動步驟數、步驟順序敏感度與環境差異。
- 目前可診斷性與平均恢復時間。
- 能否以 deterministic preflight／verification 消除。

只有已觀察證據或 operation ledger 支持的項目才進 tooling backlog；不以猜測堆積腳本。

### 5.2 初始優先序

P0：

1. 全 dev lifecycle umbrella coordination：start／stop／restart、atomic state、per-run logs、verified stop。
2. Maven coverage closure：mvn-safe、dev runtime build、Spotless 與所有 repository-owned Maven entrypoint
   使用相同 target lease；偵測 runner 死亡但 child 尚存。Immutable artifact 上線前，任何既有
   spring-boot:run 必須持有對應 target lease 全生命週期，其他 writer 回 BUSY。
3. Docker doctor／recovery：將 machine-wide repair 從 ordinary start 的隱性步驟拆成受控 adapter，
   有 consumer 時拒絕 shutdown，輸出精準 failure code。
4. Read-only doctor 與 recovery receipt：下一輪先知道 READY、BUSY、DIRTY 或 BLOCKED，再決定動作。
5. Dispatcher fail-closed stop：安全 pause／drain contract 尚未存在時，stop／restart 不得越過唯讀
   lane 不確定性；完整 operator integration 留在 Phase 3。

P1：

1. Safe cleanup／handoff：owner-scoped ephemeral cleanup、quarantine 與 destructive preview。
2. Root 與 Dispatcher immutable runtime artifact：各自在 target lease 內 build，複製到帶 generation
   的 runtime 目錄後以 Java 啟動，避免兩個長時間 spring-boot:run 與各自測試共寫 target。
3. Compose identity：顯式 project／labels，透過 label 查找而非模糊 container name；持久 volume 明確標記。
4. Dispatcher pause／drain integration，消除單次 lane snapshot 到 taskkill 的 TOCTOU。
5. LINE E2E correlation 與 local-only status 分離，避免另一輪 200 被誤認成本輪成功。
6. Testcontainers capacity、owner label 與 abrupt-exit orphan reconcile。

P2：

1. session/worktree admission、Git mutation claim 與 Flyway version reservation。
2. 失敗率報表與 tooling backlog 自動候選摘要。
3. 在有量測證據後，才把 coarse lock 拆成更多可安全並行的 resource lane。

候選入口名稱在 Phase 0 freeze，優先保留 dev-start、dev-stop、dev-restart、dev-status、mvn-safe 的相容介面，
讓既有 AGENTS.md 與人工操作不必一次重寫。共用核心、doctor、cleanup 與 handoff 可新增專用腳本。

## 6. 分階段實作與 hard gates

### Phase 0：設計 freeze 與安全邊界

工作：

- 以第 1.2 節起跑預設為基線，freeze resource key、shared/exclusive/counting compatibility、上述 total
  order、outcome／exit-code taxonomy；只有 prototype 證據顯示不可行時才選等價 safe fallback。
- 決定 machine-global 與 repo-common registry root、Windows lock backend及跨 clone／worktree可見性，
  建立 threat／failure model。
- 定義 disposable、shared、persistent、uncertain 四種 cleanup class。
- 定義 fake Docker／process／Maven adapters，讓競態與故障注入不碰 live data。
- 列出每個現有 script 的 compatibility contract 與 rollout switch。
- 拍板 Compose failure-injection 策略。推薦保留 shared main／Dispatcher project identity 與既有 volume，
  另建無固定 container name、隨機／配置 port、明確 disposable volume 的測試 fixture；若改為全面
  參數化，必須先有既有 volume identity／data continuity migration gate。
- 限定 concurrency guarantee 為 repository-owned wrappers；IDE／direct mvnw／docker 要能被 doctor
  偵測並 fail closed，或明列 unsupported bypass，不能宣稱技術上已阻止所有外部程序。
- 將三個 local live evaluation 分到 shared-live-evaluation lane，並明定一般 integration context 未隔離
  前禁止 JUnit class／method parallel。

Gate：

- 架構、active plan、resource matrix、destructive boundary 與測試 oracle 一致。
- 沒有把 Dispatcher DB、主 DB 或 Redis 當 bootstrap lock。
- machine-global keys 能跨 clone 保護 daemon、fixed ports、runtime、ngrok／LINE 與 capacity；
  repo-common／worktree keys 不被誤用成 host-global guarantee。
- lock compatibility 與 total order 能表達 Docker repair vs consumers、runtime transition vs consumers、
  migration vs runtime/live-evaluation consumers，且沒有 upgrade 路徑。
- Compose fixture 能在不改變 shared dev volume identity 的情況下做 disposable failure injection。
- 任何尚待使用者決策都有推薦值、影響與 safe fallback。

#### Phase 0 freeze record（2026-07-22）

- **Coordinator schema 與 backend**：所有 key 採 `v1/{scope}/{resource-type}/{normalized-key}`；mutex 名稱為
  `Global\\mms-coord-v1-{SHA-256(key)}`。每個 resource 有一把 gate mutex 與固定數量的 slot mutex：shared／counting
  operation 在 gate 內取得一個 slot 後持有至 operation 結束；exclusive operation 持有 gate 至 operation 結束，並
  取得全部 slots。此方式不允許 upgrade，且 owner crash 由 Windows abandoned mutex 釋放。slot 數即 counting capacity；
  shared resource 也明確宣告 bounded capacity，未取得者回 BUSY，不以 registry 猜測接手。
- **registry roots 與 visibility**：machine registry root 為
  `%LOCALAPPDATA%\\my-mobile-secretary\\coordination\\v1\\machine`；repo-common root 為
  `%LOCALAPPDATA%\\my-mobile-secretary\\coordination\\v1\\repos\\{SHA-256(normalized Git common-dir)}`。machine key
  的 Global mutex 跨同機 clone；repo key 只以 Git common-dir 共用同一 clone 的 worktree，不能宣稱跨 clone。
  registry JSON 僅為可觀測 metadata，以同目錄 temporary file 加 atomic replace 寫入；named mutex／slot handle 才是
  correctness authority。`LOCALAPPDATA` 不可用、Global mutex 被拒絕、或不同 Windows logon 可見性未經 doctor
  證實時，一律 BLOCKED，不降級為 repo-local lock。
- **compatibility／order**：採第 3 節 rank、type、ordinal key 的 canonical total order；同一 key 不可重複、不可
  upgrade。counting resource 必須同時宣告其 `machine/docker-daemon` shared key；Docker repair、environment
  transition、Flyway migration 與 live-evaluation consumer 均由既有 matrix 的 exclusive/shared 宣告表達。
- **generation、outcome 與 exit code**：operation ID 為 GUID；每 resource generation 在 registry CAS 成功後遞增，
  receipt 帶 operation ID、generation 與 fencing token。穩定 outcome 為 READY、DEGRADED、BUSY、
  RECOVERY_REQUIRED、BLOCKED；coordinator CLI exit code 依序為 0、10、20、30、40，未分類內部失敗為 50，
  argument／schema error 為 64。現有 wrappers 在 rollout 前維持既有 0／1／2 contract，receipt 只作觀測，
  不把新 code 偷偷傳回既有 caller。
- **cleanup class**：`disposable` 僅限 operation 建立、建立時已標記且 owner／operation／generation 完全相符的
  fixture；`shared` 保留健康共享 runtime／container；`persistent`（dev volume、Flyway history、Git/source、
  secrets、shared Redis keys）只記錄；`uncertain`（owner、PID、Dispatcher、外部 resource 任一無法驗證）一律
  quarantined/BLOCKED。任何 timeout 或 heartbeat 只觸發 audit，不能直接 cleanup 或 takeover。
- **fake adapters**：Phase 1 kernel 注入 filesystem、clock、process、Docker 與 Maven adapter；fake adapter 只可建立
  test root 下標記 disposable 的 state／resource，並可注入 acquire timeout、CAS replace 前 crash、owner crash、
  child residual 與錯誤 exit。它們不得呼叫 docker、mvnw、taskkill、LINE、ngrok 或 Dispatcher DB。
- **existing entrypoint compatibility／rollout**：`mvn-safe.ps1`、`spotless-apply.ps1`、`dev-start.ps1`、
  `dev-stop.ps1`、`dev-restart.ps1`、`dev-status.ps1` 保留名稱、參數與既有 exit behavior；Phase 1 僅新增
  read-only doctor/kernel，Phase 2 Maven adapter 預設 disabled，Phase 3 才以 feature switch 將 lifecycle
  wrapper 包入 transition lease，Phase 5 經觀察期後移除已記錄 bypass。IDE、direct mvnw、direct docker
  僅能由 doctor 偵測，偵測到就 fail closed，不能聲稱攔截。
- **fixture 與 test lane**：shared main／Dispatcher Compose project、fixed names、host ports 和 persistent volume
  identity 完全不改；failure injection 另用新的 Compose fixture，無固定 container name、使用隨機／明確測試 port、
  disposable volume 與 operation/generation labels。三個 opt-in local live evaluation 固定為
  `shared-live-evaluation` consumer lane；一般 integration context 在 per-test infra 尚未隔離前維持
  JUnit class／method serial。
- **threat model**：registry lost/corrupt、atomic replace 前 crash、owner crash、stale receipt、PID reuse、
  unmanaged writer、跨 clone、Docker daemon identity unknown、Dispatcher active/unknown 都視為未驗證狀態；
  除 registry temp 之外不自動移除任何物件，且只在 owner proof 完整時才 reconcile disposable residual。

### Phase 1：Coordinator kernel、atomic registry 與 receipts

工作：

- 實作 resource declaration、RW／counting compatibility、固定排序 acquire/release、generation／fencing
  與 bounded wait。
- 實作 atomic state replace、CAS、per-operation receipt 與 per-service-generation log directory。
- 新增 read-only doctor；reconcile 先只報告，不自動清理。
- 建立可注入 clock／filesystem／process adapter 的 PowerShell 測試 harness。

Gate：

- 20 個並行 PowerShell process 爭用同一 key，critical-section 最大同時數恆為 1。
- multi-key 取得無 deadlock；timeout／cancel 回 BUSY 且零 mutation，已取得的部分 locks 全部反向釋放。
- crash／kill owner 後 lock 可釋放，manifest 會標 abandoned，generation 不倒退。
- 一般 PowerShell、Codex sandbox、不同 worktree與不同 clone通過應有的 machine/repo scope visibility
  matrix；若 Windows logon boundary 無法支援，明列限制並 fail closed。
- 連續並行讀寫不產生無效 JSON、lost update 或跨 run log 覆寫；transition receipt 結束後不會清除
  active service generation 的 logs。

### Phase 2：Maven、Spotless 與 immutable runtime artifact

工作：

- 將 root 與 Dispatcher Maven writer 接入各自 target lease。
- Phase 2 若會觸及 live start，第一步就讓 coarse environment exclusive transition 包住整次 mutation；
  否則新 adapter 必須保持 disabled，直到 Phase 3 才切換既有 dev-start。
- 主程式與 Dispatcher 分別改為 lease 內 build／copy immutable artifact、lease 外啟動，不再讓兩個
  長駐 Maven writer 與各自測試共用 mutable target。
- Spotless apply 另取得 source-write claim；check 只跟隨 target lease。
- cancel／timeout 必須終止並驗證完整 child tree；殘留 child 先阻擋下一 writer。

Gate：

- start build、test、clean、Spotless 任意兩兩競爭時，同一 target 最大 writer 為 1。
- runner 被 kill、child 保留的情境會回 RECOVERY_REQUIRED，不會因 abandoned mutex 開第二個 writer。
- root 與 Dispatcher runtime 各自使用 immutable artifact generation，不會被後續測試改寫。
- Immutable path 尚未啟用時，spring-boot:run 會持 target lease 全生命週期，對應測試明確回 BUSY。
- 兩個 start 競爭時不會同時 build／copy／start；disabled adapter 不會改 live runtime。
- 不以 clean 作一般恢復；只有 suspect evidence 命中時才允許受控 clean。

### Phase 3：Docker、Compose 與服務生命週期

工作：

- start／stop／restart 全段取得 environment exclusive；full restart 不在中間釋放，shared consumers
  必須先 drain 或讓 transition 回 BUSY。
- Docker ordinary startup 與 destructive repair 分離；所有 Docker clients 持 daemon shared，repair
  持 daemon exclusive。Daemon／consumer 狀態不可查時 repair BLOCKED。
- Shared Compose 加入穩定 identity／owner labels且保持既有 project／volume data continuity；disposable
  failure-injection fixture 使用無固定名稱與隨機／配置 port。
- Stop-ProcessTree 回傳結構化結果，等待 PID tree、port 與 health 達到預期終態。
- Dispatcher 停機改用 durable pause／drain contract；unknown outcome 一律 BLOCKED。
- Service ownership 以實際 profile、artifact/config digest、Dispatcher mode、ngrok/LINE mode、
  Flyway version與 generation 為準；SkipDispatcher／NoNgrok 不得清空或覆寫未觸及資源。

Gate：

- start、stop、restart、down 任意競爭不交錯，且只有一份 terminal receipt。
- 每個 startup phase 注入失敗後，created／reused resource disposition 符合 policy。
- Docker daemon 已恢復時第二個 repair 不會再 shutdown；有 consumer 時 repair 拒絕。
- taskkill 非零、PID 未退或 port 未釋放時不得清 ownership 或宣告成功。
- Dispatcher 在 drain gate 後不能開始新 run；既有 active/unknown run 不會被殺或清 DB。
- SkipDispatcher 保留的 PID／mode 不被本輪 ArmDispatcher default 覆寫；NoNgrok 不會把仍在跑的 tunnel
  ownership 寫成 null。
- shared Compose identity／volume continuity 驗證通過；disposable fixture 不碰 shared volume。

### Phase 4：測試 lane、多 agent 與 owner-scoped cleanup

工作：

- 對 Docker-heavy suites 實作 capacity semaphore；Testcontainers 加自訂 operation／generation labels
  與 reconcile，不碰 Ryuk、只有通用 Testcontainers label 的資源或預設 bridge network。
- 一般 Testcontainers integration 保持 class／method serial；local live evaluation 取得
  environment/data shared consumer lease，或改成真正隔離 infra 後才可進 isolated lane。
- session/worktree admission、Git mutation claim、Flyway sequence reservation。
- handoff／cleanup 命令依 resource class 執行；persistent/destructive 只 preview 並要求批准。
- status 拆分 local snapshot 與 opt-in external LINE probe，加入 request correlation。

Gate：

- 不同 worktree 的純 unit test 可並行；同 target writer 仍不重疊。
- abrupt Testcontainers exit 只會清理自訂 owner label 完全匹配的 disposable container/network/volume。
- 同時 reserve migration 只有一個版本 owner；衝突不會自動改已存在 migration。
- session end 不會刪 source、Git state、shared dev volume、Flyway history、Redis shared keys 或
  Dispatcher run；只刪建立時已標 disposable 且 owner 完全匹配的測試 volume。
- 下一 session 可只讀 receipt 與 doctor 結果，決定繼續、等待或 recovery，不需先 blanket clean。

### Phase 5：Rollout、文件與可靠度回饋

工作：

- 既有 scripts 先成為 coordinator adapters，經觀察期後才移除舊 bypass。
- 更新 AGENTS.md、docs/test-strategy.md、docs/decisions/current.md 與 operator runbook。
- operation ledger 產生失敗率／恢復時間摘要，將已觀察高風險項送入 tooling backlog。
- 做一次 live、非 destructive smoke；volume removal、Flyway failure repair 與真實 Codex run 僅驗證拒絕路徑。

Gate：

- 所有 repository-owned 啟停、Maven、Spotless、Compose mutation 都無未記錄 bypass；偵測到 IDE／
  direct unmanaged writer 時 fail closed 並回報，不能宣稱 coordinator 已控制外部程序。
- 至少完成一次成功、一次 injected failure、一次 owner crash 的 end-to-end handoff。
- ordinary workflow 不要求使用者手動判讀 PID／container；錯誤仍保留可追溯 bounded diagnostics。
- 文件中的命令、exit code、cleanup class 與實際工具一致。

## 7. Failure-injection acceptance matrix

| Scenario | Oracle |
| --- | --- |
| 兩個 start 同時執行 | 一個取得 transition；另一個等待或在 READY 後重用，不能建立第二個 runtime |
| start 與 full restart 競爭 | transition 不交錯；後取得者重新 preflight，不使用舊快照 |
| dev runtime build 與 root test 競爭 | target writer 恆為 1；running service artifact immutable |
| Docker repair 與 Testcontainers suite 競爭 | repair 回 BUSY/BLOCKED；不 shutdown backend |
| registry writer 在 replace 前被 kill | 保留上一個完整 generation；temp 可 owner-scoped 回收 |
| owner 建立外部 resource 後、VERIFY／receipt 前被 kill | 下一輪標 abandoned 並 reconcile；不把 resource 誤認成 foreign 或直接刪除 |
| stop 的 taskkill 失敗 | outcome 為 RECOVERY_REQUIRED，PID ownership 不清空 |
| startup 在 ngrok／app／Dispatcher／LINE gate 任一點失敗 | 只清本輪 created ephemeral；reused/shared 明列 retained |
| PID 被重用 | start time／command／port／token 任一不符就拒絕 kill |
| loser Maven process 讀到 winner 的 health endpoint | health PID/run generation 不符，loser 不得宣告 READY |
| Dispatcher lane check 後嘗試 claim | durable drain 阻止新 run，或 stop fail closed |
| Flyway migration 失敗 | DB quarantine；不 clean、repair、刪 history 或 volume |
| Testcontainers owner process 被 kill | 只處理 labels 與 generation 完全相符的 disposable residual |
| destructive cleanup 與 active consumer 競爭 | 拒絕且零刪除；receipt 列出 blocker |
| main down-v 成功、Dispatcher down-v 失敗 | receipt 分資源記錄不可逆 partial；不得以整體 success 或 rollback 掩蓋 |
| 兩個 LINE probe 同時發送 | request correlation 各自歸因，不共用另一輪 request |
| status 與 transition 同時執行 | status 讀到單一 generation，否則回 STALE／BUSY，不拼接跨時點健康狀態 |
| SkipDispatcher／NoNgrok 保留既有資源 | untouched 的 PID、mode、URL/config 不被本輪 defaults 清空或覆寫 |
| local live evaluation 與 restart 競爭 | evaluation 持 shared consumer；restart 等待或回 BUSY |
| 嘗試開啟 JUnit method/class parallel | 在共用 integration context 下 gate 拒絕，除非每測試 infra 已隔離 |
| handoff 時 worktree dirty | source 保留，列 owner／paths；不以 cleanup 還原 |

Live failure injection 禁止直接對目前 persistent dev volume、真實 LINE endpoint 或 active Dispatcher run 執行。
先使用 fake adapter／disposable Compose project；需要擴到 live smoke 時另列精確目標與使用者批准。

## 8. 驗證命令與執行時機

本輪是純文件規劃，只執行精準內容／連結檢查與：

```powershell
git status --short
```

以下路徑是各 Phase 的明確 deliverable，建立前不可假裝已執行；每個 gate 必須把實際 exit code、
通過／失敗數與 skipped live paths 回填本計畫：

```powershell
# Phase 1：純 fake adapter；跨 process、multi-key、CAS、cancel 與 crash
powershell -ExecutionPolicy Bypass -File .\scripts\tests\coordination-kernel-test.ps1

# Phase 2：純 fake process/Maven adapter；root 與 Dispatcher target/artifact
powershell -ExecutionPolicy Bypass -File .\scripts\tests\coordination-maven-test.ps1

# Phase 3：先只跑 fake Docker/process adapter
powershell -ExecutionPolicy Bypass -File .\scripts\tests\coordination-lifecycle-test.ps1 -Adapter Fake

# Phase 4：只用明確 disposable fixture；不得指向 shared dev project/volume
powershell -ExecutionPolicy Bypass -File .\scripts\tests\coordination-handoff-test.ps1 -Adapter Disposable

# 每階段文件／狀態 smoke；不得觸發 LINE external probe
powershell -ExecutionPolicy Bypass -File .\scripts\dev-doctor.ps1 -Json -NoExternalProbe
```

接入真實 root Maven 後，一律仍從 coordinator-aware mvn-safe 執行精準 test-compile／selected tests；
Dispatcher 使用其獨立 target adapter。Phase 2 gate 前不得以直接 mvnw 作為協調驗收。Live Docker smoke、
LINE probe、volume removal、Flyway failure repair 與真實 Codex process 都不是預設命令，必須另列目標、
consumer 狀態與批准。

## 9. 預計影響範圍

文件：

- docs/architecture.md：長期協調平面、資源所有權與清理不變量。
- docs/decisions/current.md：Phase 0 拍板後摘錄 durable decisions。
- docs/test-strategy.md：隔離、capacity、禁止 persistent dev DB 測試與 concurrency gates。
- docs/agent-context/execution-plan-policy.md：多 session 計畫必備 resource／handoff 內容。
- 本 active plan 與 active index：階段、gate、驗證證據與歷史。

工具候選：

- scripts/_devops-common.ps1、mvn-safe.ps1、dev-start.ps1、dev-stop.ps1、dev-restart.ps1、
  dev-status.ps1、spotless-apply.ps1。
- 新的 coordination common、doctor、handoff、cleanup 與 PowerShell concurrency test harness。
- compose.yaml 與 internal/ai-dispatcher 的對應 Compose／operator adapter。
- .gitignore：只加入精確的 machine-local state／receipt／runtime artifact 路徑；不得忽略一般來源。

產品 Java、主 Flyway schema 與產品 domain 不在本計畫範圍。Dispatcher 若需 pause／drain management
contract，只能改其獨立 application／DB／tests，且仍不得形成主應用依賴。

## 10. 未測路徑與剩餘風險

- 本輪沒有 runtime telemetry，初始優先序來自使用者反覆遇到的症狀與靜態競態證據；Phase 1 後用 ledger 校正。
- Machine-global lock／registry 在不同 Windows logon session、Codex sandbox 與不同 clone 的可見性尚待
  Phase 0/1 驗證；無法證明時必須 fail closed，不能降級成 repo-local 假保護。
- Docker context 可能指向同一 daemon；daemon identity 不可取得時，repair 與 destructive Compose
  operation 仍可能 BLOCKED。
- IDE 或人工直接執行 mvnw／docker 仍可 bypass。Rollout 需文件、wrapper 與 doctor 偵測，無法只靠善意。
- immutable runtime artifact 會增加 build／copy 成本與 retention 需求，需量測但不能以共寫 target 換速度。
- 多 agent 同檔編輯無法由 Docker/Maven lock 解決；仍需 worktree／任務分區與 Git review。
- Dispatcher pause／drain 若尚無安全 management contract，不得以 DB script 取代；Phase 3 可因此停在 BLOCKED。
- Shared consumer lease 只能保護有經 coordinator 進入的 client；未管理的外部呼叫仍需以 operator policy
  與 bounded transition timeout 處理。
- 另建 disposable Compose fixture 是目前推薦值；若 Phase 0 改採全面參數化，shared volume identity
  migration 會成為新的 destructive/data-continuity gate。

## 11. Context 壓縮候選點

以下是可供 agent 判斷的穩定出口，不是唯一或強制時機。實際時機依根 `AGENTS.md` 自主決定；agent
可以依當下開發品質、開發效率與 token 效率提前、延後、跳過或新增候選點，但不得在 decision 未提交、
destructive operation 中途、未知測試失敗或仍依賴大量未摘要輸出時建議壓縮。

### 提醒點 0：現況盤點收斂

觸發：靜態稽核、resource matrix、初始風險排序與 active plan 已完成，尚未開始 Phase 0 decision freeze
或任何工具實作。

壓縮後必須保留：

- 目前階段：盤點完成，Phase 0 架構決策 gate 進行中。
- 已確認方向：需要跨 session 自協調、owner-scoped cleanup、handoff 與高失敗率操作腳本化。
- 不變量：主 runtime 不依賴 coordinator／Dispatcher；persistent／uncertain state 不自動清理。
- 修改檔案：本 active plan、architecture 第 36 節、active index、current decisions 入口與 execution-plan policy。
- 驗證：相對連結、必備章節與文件狀態措辭；Docker／Maven／runtime tests 全部因純規劃而跳過。
- 未完成：第 1.2 節預設、lock backend／registry root、resource key／exit code freeze。
- 下一步：完成 Phase 0，先用 fake adapter 驗證，不接 live Docker。
- 風險：現有 scripts 尚不具 concurrency guarantee，人工／IDE direct command 仍可 bypass。

### 提醒點 A：Phase 0 gate 通過

觸發：resource matrix、coordinator owner、lock backend、registry root、cleanup classes、exit codes 與 rollout
預設已拍板並寫入文件。

壓縮後必須保留：

- 目前階段：Phase 0 完成，下一步 Phase 1 kernel。
- 已拍板決策與不變量：共享／隔離模型、Dispatcher 邊界、destructive policy、lock ordering。
- 修改檔案：完整清單。
- 驗證：文件一致性與任何 lock prototype 結果；失敗／跳過 gate。
- 未完成：Phase 1 tasks 與第一個測試命令。
- 風險／阻塞：sandbox、跨 Windows session、使用者尚待批准項。

### 提醒點 B：Phase 1 kernel gate 通過

觸發：並行鎖、atomic registry、crash recovery、receipt 與 doctor fake-adapter tests 全部通過，尚未接 live
Maven／Docker。

壓縮後必須保留：

- 目前階段：Phase 1 完成，下一步 Phase 2 Maven/runtime。
- 已拍板 schema、resource keys、generation／fencing 與 outcome taxonomy。
- 修改檔案與新增測試 harness。
- 20-process、CAS、deadlock、owner crash 驗證結果。
- 未完成 adapters、相容入口與下一個最小 integration。
- 已知 residual／平台限制。
- 阻塞／待使用者決策：無則明寫「無」；否則列 exact resource、safe fallback 與是否禁止接 live adapter。

### 提醒點 C：Phase 3 lifecycle gate 通過

觸發：Maven/runtime 與 Docker/Compose/service lifecycle 已整合，所有 injected failure 已定位並通過，
尚未進多 agent cleanup/handoff rollout。

壓縮後必須保留：

- 目前階段：Phase 3 完成，下一步 Phase 4。
- immutable artifact、Docker recovery、Compose identity、Dispatcher drain 的決策。
- 所有修改檔案、驗證結果、live smoke 是否跳過。
- retained／removed／quarantined 的實際 oracle。
- 未完成 Testcontainers capacity、worktree/migration claim、handoff。
- persistent data 與 active Dispatcher 風險。
- 阻塞／待使用者決策：列出任何 daemon visibility、Dispatcher drain、volume continuity 或 live smoke 批准；
  無則明寫「無」。

### 提醒點 D：Phase 5 release gate 通過

觸發：所有入口已接 coordinator、故障／崩潰 handoff 驗收完成、文件與操作命令一致。

壓縮後必須保留：

- 目前階段：計畫完成，準備移入 completed。
- 最終 durable decisions 與仍禁止的 destructive／bypass 操作。
- 修改檔案與完整驗證摘要。
- operation ledger 的基線失敗率與未測路徑。
- 尚存風險、後續 tooling backlog 與下一次 review 時點。
- 阻塞／待使用者決策：無則明寫「無」；有則不得把計畫移入 completed。

## 12. 執行紀錄

### 2026-07-22：現況盤點與計畫建立

- 已讀任務路由、架構／決策／計畫政策與 Testcontainers 基線。
- 已稽核 dev-start／stop／restart／status、mvn-safe、Docker recovery、Compose 與 Dispatcher race boundary。
- 已確認一般 root integration tests 使用獨立 Testcontainers，但 target 與 Docker capacity 仍是共享競態；
  三個 opt-in local live evaluation 另可能接 shared Compose，不能歸入隔離 lane。
- 已完成 resource matrix、P0-P2 腳本化排序、Phase 0-5 gates 與 context 壓縮提醒點。
- 尚未修改任何工具、Compose、runtime、migration 或測試行為；下一步是 Phase 0 決策 freeze。

### 2026-07-22：Terra High handoff 準備

- 本計畫已標為 Terra High 可執行版本，唯一入口為 Phase 0，通過後必須直接進 Phase 1 實作。
- 第 1.2 節安全建議已提升為起跑預設；backend／registry path 仍由 Phase 0 prototype 證據 freeze。
- 新增獨立 copy-paste 啟動提示詞；未修改 scripts、Compose、runtime、migration 或測試行為。

### 2026-07-22：Phase 0 gate 通過

- 實際命令：`git status --short`（exit 0；既存 dirty/untracked 文件全數保留）、同一 Windows 使用者的
  `Global\\my-mobile-secretary-coordination-prototype` parent/child PowerShell mutex prototype（exit 0；child
  `ChildAcquired=false`）、以及 5 項文件 freeze consistency check（exit 0；checked=5、missing=0）。
- 已 freeze：key／order／compatibility、Global gate/slot backend、LOCALAPPDATA registry root、atomic replace、
  generation/fencing、cleanup classes、outcome/exit codes、fake adapter、existing-wrapper rollout 及 disposable
  Compose fixture 策略。
- skipped live paths：Docker、Compose、LINE、ngrok、Maven lifecycle、dev-start／stop／restart；residual：無本輪
  建立資源，既有 worktree dirty 依原狀保留；cleanup disposition：不執行 cleanup。
- 風險：不同 Windows logon visibility 尚未有可重現證據，Phase 1 doctor 必須回報並 fail closed；IDE/direct
  command 仍是 unmanaged bypass。下一步：Phase 1 fake-only kernel 與 concurrency/crash/CAS gate。

### 2026-07-22：Phase 1 kernel gate 通過

- 實際命令：`powershell -ExecutionPolicy Bypass -File .\\scripts\\tests\\coordination-kernel-test.ps1`
  （exit 0；11/11 assertions passed；20 process；maximum critical section=1；CAS generation=200）；
  `powershell -ExecutionPolicy Bypass -File .\\scripts\\dev-doctor.ps1 -Json -NoExternalProbe`（exit 10，預期
  DEGRADED；外部 probe skipped）；三個新增 PowerShell script parse check（exit 0；3 files、0 errors）。
- 已驗證：canonical multi-key 無 deadlock；timeout BUSY 時 partial lease 反向釋放；killed owner 的 mutex 可
  取得、manifest 唯讀解析為 ABANDONED；registry CAS 無 lost update/invalid JSON；receipt 不覆寫 active
  service-generation log；machine lock 不依 clone/worktree path，repo root 只共享 Git common-dir。
- skipped live paths：Docker、Compose、LINE、ngrok、真實 Maven、dev lifecycle；residual：test root 為
  disposable，測試 finally 已移除；shared/persistent/uncertain resource 零觸及、零 cleanup。
- 風險：不同 Windows logon visibility 未驗證，doctor 為 DEGRADED、不可驗證 mutation 必須 BLOCKED；IDE/direct
  command 尚未進 doctor 偵測。下一步：Phase 2 fake Maven/immutable-artifact adapter，保持 disabled。

### 2026-07-22：Phase 2 fake-adapter gate 通過

- 實際命令：`powershell -ExecutionPolicy Bypass -File .\\scripts\\tests\\coordination-maven-test.ps1`
  （exit 0；8/8 assertions passed；root immutable artifacts=8；Dispatcher artifact=true），並重跑
  `coordination-kernel-test.ps1`（exit 0；11/11 assertions passed）。
- 已驗證：Build/Test/Clean/SpotlessApply/SpringBootRun fake writers 對同一 root target 只經同一 lease；
  Dispatcher target 獨立；每個 successful run 產生 immutable artifact；disabled adapter 不改 runtime；持有
  SpringBootRun lease 的競爭者回 BUSY；fake child residual 使後續 writer 回 RECOVERY_REQUIRED，未自動 clean。
- skipped live paths：真實 root/Dispatcher Maven、Docker、Compose、LINE、ngrok、dev lifecycle；residual：test
  root disposable 且已移除，零 persistent cleanup。下一步：Phase 3 先確認 Dispatcher durable pause/drain
  contract，只有 fake lifecycle gate 通過後才考慮 adapter integration。

### 2026-07-22：Phase 3 fake lifecycle 子 gate

- 實際命令：`powershell -ExecutionPolicy Bypass -File .\\scripts\\tests\\coordination-lifecycle-test.ps1 -Adapter Fake`
  （exit 0；5/5 assertions passed；live paths skipped）。已驗證 transition 不交錯、unknown Dispatcher outcome
  BLOCKED、Docker consumer 存在時 repair BUSY，以及 stop verification failure 回 RECOVERY_REQUIRED。
- 未通過完整 Phase 3 gate：既有 Dispatcher 僅有 protected session-binding API；沒有 coordinator 可安全呼叫的
  durable pause/drain management contract。不得以 lane DB query／row mutation 或未設定的 management token 取代。
  因此既有 `dev-start/stop/restart`、Compose、Docker、LINE/ngrok 全部尚未切換；下一步需要先新增並驗證
  Dispatcher pause/drain contract，且 live integration 另需專用管理 token、明確 target/consumer state 與使用者批准。

### 2026-07-22：Phase 3 Dispatcher drain contract（進行中）

- 已新增 Dispatcher 專屬 V8 drain-request persistence、transactional drain/resume service、受既有 session-binding
  admin token 保護的 endpoint，且 active run 完成時會尊重已持久化的 drain request 轉入 PAUSED。
- 驗證：`internal/ai-dispatcher` `-DskipTests test-compile` 首次 exit 1（已知 `Cannot close compiler resources`），
  依 repository 規範以相同無 clean 命令在 sandbox 外重試 exit 0。Docker/Compose/runtime/management API 均 skipped。
- 未完成：Dispatcher integration test、operation audit、coordinator adapter 實接與任何 live smoke；在沒有專用
  management token、明確 consumer state 與 live approval 前，所有真實 pause/drain 呼叫保持 BLOCKED。

### 2026-07-22：Phase 3 Dispatcher drain API gate

- 新增 `V8__add_coordinator_drain_request.sql`、durable drain request/audit、protected
  `/internal/v1/dispatcher-drain` endpoint，並使 active run terminal transition 尊重已持久化 drain request。
- `SessionBindingControllerIntegrationTest`：首次 sandbox compile exit 1（已知 compiler resource close）；相同無
  clean command sandbox 外重試 exit 0，7 tests、0 failures/errors/skipped。使用隔離 Testcontainers PostgreSQL；
  shared Compose、live Dispatcher、LINE、ngrok 與 dev lifecycle 全部 skipped，無 shared residual cleanup。
- 未完成：active run drain-to-PAUSED regression、adapter integration、Compose identity/volume continuity、受控 live
  smoke；管理 token 未提供前真實 request 仍 BLOCKED。

### 2026-07-22：Phase 3 drain lifecycle regression 通過

- `CodexLifecycleServiceIntegrationTest` 與 `SessionBindingControllerIntegrationTest` 共 14 tests、0 failures/errors。
  active run terminal transition 的 drain-to-PAUSED、drain/resume API、actor audit 及既有 finish behavior 均已驗證。
- 首次 sandbox test-compile 仍遇已知 compiler resource close；同一無 clean command sandbox 外重試成功。測試只用
  disposable Testcontainers PostgreSQL；shared Compose/live Dispatcher/dev lifecycle 全部未操作。

### 2026-07-22：Phase 3 live drain smoke

- 使用者在已設定專用 token 的本機 PowerShell 重啟目前 Dispatcher 後，對 protected drain endpoint 執行一次
  drain → resume；terminal response 為 `RESUMED`。依 resume contract，這只可能來自 `COORDINATOR_DRAIN` pause，
  因此 drain 已完成且沒有 active/unknown run 被清除或中斷。
- 本 smoke 未執行 Docker/Compose mutation、volume cleanup、Flyway repair、LINE probe 或 Dispatcher DB direct
  mutation；main/Dispatcher restart 由既有 wrapper 完成，環境回到 ready/disarmed state。

### 2026-07-22：Phase 4 disposable handoff gates

- `coordination-handoff-test.ps1 -Adapter Disposable` exit 0，8/8 assertions passed：receipt 保留 persistent state、
  cleanup=none、同版本 Flyway reservation 與同 worktree source writer 的跨 process competitor 均回 BUSY。
- 未建立/改名 migration、未改 Git index/source、未清 shared volume/Flyway history/Redis/Dispatcher run；test root
  為 disposable 且由 test finally 移除。

### 2026-07-22：Phase 5 rollout 與 release gate 通過

- repository-owned `mvn-safe.ps1`、`spotless-apply.ps1`、`dev-start/stop/restart` 已接入 coordinator lease；長駐
  Spring Boot/Dispatcher 由 `coordinated-maven-run.ps1` 在其完整存活期持有各自 target lease。lifecycle switch
  啟用時，outer transition 以 machine Docker/port/ngrok/environment resources 取得單一 exclusive lease，nested
  script 透過 inherited marker 不重入。
- `dev-doctor.ps1` 除 port ownership 外，也會辨識 direct Maven／Compose command line；`coordination-doctor-test.ps1`
  exit 0（3/3 assertions）。未知或 untracked writer 一律 BLOCKED，不會停止或清理它。
- `dev-status.ps1` 已拆為 local snapshot 與明確 `-ExternalLineProbe`；舊 state 缺少 service generation 時安全降級。
  local-only smoke 在服務未啟動時預期 exit 1，且 LINE external probe 明確 skipped，沒有 mutation。
- final live smoke：修正 `dev-restart.ps1` 的 StrictMode progress 預設後，既有 wrapper exit 0；在相同權限層以
  `dev-status.ps1 -NoNgrokRequired` 確認 main、Postgres、Redis 與 Dispatcher 都 healthy，LINE external probe skipped。
- 實際命令：所有 19 個協調 PowerShell 檔 parse exit 0；kernel 12/12、Maven 13/13、lifecycle fake 8/8、handoff
  disposable 8/8、doctor 3/3 全部 exit 0；`powershell -ExecutionPolicy Bypass -File .\\scripts\\mvn-safe.ps1
  -DskipTests test-compile` exit 0。這次 Maven real adapter 使用 lease、未 clean、未啟動 Docker 或 Testcontainers。
- end-to-end evidence：fake success、injected failure、owner crash 與 handoff receipt 均在 disposable state 驗證；先前
  protected Dispatcher drain → resume live smoke 已回 `RESUMED`。本 gate 不執行 volume removal、Flyway repair 或真實
  Codex run；它們的拒絕/retain policy 維持有效。
- 殘餘風險：跨 Windows logon visibility 仍為 doctor DEGRADED；未經 wrapper 的 IDE/direct command 僅能偵測、不能
  預先攔截。Testcontainers generic resources 沒有 owner proof 時只 preview/retain，絕不以 Ryuk/default label 或 bridge
  network 作 cleanup target。下一步：可將本計畫移至 completed；無待使用者決策。

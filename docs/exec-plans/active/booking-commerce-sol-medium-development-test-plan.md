# Booking／Commerce Execution — SOL Medium 開發與驗證計畫

> 狀態：B0–B2 PASS；B3 等待筆電發布可由 Git 取得的 B2 handoff 後，由桌電重新取得 source claim
>
> 執行提示詞：`booking-commerce-sol-medium-prompt.md`
>
> 第一個 release gate：deterministic regression ＋ Duffel test mode；不要求真實扣款
>
> 雙機執行邊界：`two-machine-parallel-development-plan.md` 與
> `desktop-booking-add-lane-runbook.md`
>
> 跨 lane trigger：`parallel-development-trigger-registry.md` 與
> `handoffs/desktop-trigger-state.json`

## 1. 成果與範圍

目標是讓 my-mobile-secretary 在規劃旅行時分階段確認交通、住宿及後續票務是否有可信供給，
提供最佳平衡、最低總價、最彈性三種方案；使用者完成獨立購買授權後，透過外部 provider
完成 hold／book／pay，並持續監控延誤、取消、價格與訂單狀態。

第一個端到端垂直是國外機票＋住宿，先以 Duffel Flights／Stays test mode 打通。台灣交通、
藝文、展覽、餐廳、景點與租車沿用同一 provider capability contract 後逐類增加。

「行程內容已確認」與「授權購買」永遠分離。LLM 只做 structured language understanding
及表達；價格、庫存、時間、授權、替代、狀態、actor、RLS、冪等與外部 mutation 由 Java
application/domain service 決定。

## 2. 已確認產品決策

- 第一版先自用並支援受邀家人，但從第一天維持 workspace／actor／RLS 與稽核邊界。
- 查核分四層：服務覆蓋、即時庫存、正式 quote、送單前最後驗證。
- 每趟可選確認模式：逐筆、整批一次、政策範圍內自動執行。
- 每趟可選替代強度：精確候選、有限等價替代、目標導向完成。
- 新使用者介面推薦「整批一次＋精確候選」，仍需明確選擇；不可退方案必須本次明示接受。
- 部分成功時停止後續並保留成功項，不自動取消或補償。
- Provider 結果未知時先對帳，禁止盲目重送。
- 卡片由 provider-hosted/tokenized flow 處理；本系統不接觸或保存完整卡號／CVC。
- 旅客姓名、生日、護照等置於獨立加密保險庫；Project、聊天、LLM、LifeRecord 不保存原文。
- 訂後持續監控並提出 impact proposal；改票、退票、重訂仍需符合該趟授權政策。
- API 優先，官方 checkout/deep link 次之，Playwright 白名單人工接手為最後手段。
- Deterministic＋Duffel sandbox E2E 是 release gate；真實交易另行逐筆授權，不阻擋第一版。

## 3. 現有並行開發與依賴

目前 Calendar v2 Wheel 8 與 Travel 3B-A 正在同一 repository 並行。工作樹含 Calendar、
Knowledge、StoredMedia、LifeRecord、Intent、Notification、Project binding 及 V66–V81 migration
既有變更。這些變更均視為其他 session 所有，不得還原、覆寫、搬移、格式化或納入本線。

| 資源／模組 | 目前 owner／依賴 | Booking 安全行為 |
| --- | --- | --- |
| Calendar one-off lifecycle／Project binding | Calendar Wheel 8、Travel 3B-A | 穩定 handoff 與 full regression 前不接入 |
| Calendar recurrence propagation | Calendar Wheel 10、Travel 3B-B | 只阻擋 recurring booking，不阻擋 one-off |
| Travel transport／stay／traveller | Travel Wheels 6–7 | B3 接入前必須有正式型別與 PASS handoff |
| Intent／capability catalog | 多條 conversation 開發線 | B6 才修改，需獨占 source claim |
| Notification outbox／LifeRecord | Calendar 與既有共用基礎 | B7 才接，禁止另建平行機制 |
| Flyway sequence | V66–V81 已存在且仍可能增加 | B2 當輪重新查核並預約，不預寫版本 |
| Maven target／Docker／shared runtime | 主機共享且資源有限 | 單 writer、重型 gate 不並行 |
| Active docs／current decisions | 已有未提交修改 | 只在可驗證 owner 協調後精準合併 |

## 4. 核心介面與狀態

在 `booking` bounded context 定義 `searchAvailability`、`refreshQuote`、`hold`、`book`、
`getStatus`、`proposeChange`、`cancel` 與 `reconcileUnknownResult` provider-neutral SPI。

核心型別包含 `ProviderCapability`、`OfferSnapshot`、`BookingPlan`、`PurchaseAuthorization`、
`ExternalBookingOrder` 與 `BookingAttempt`。執行狀態固定為：

`PRICED → AUTHORIZED → EXECUTING → COMPLETED | PARTIALLY_COMPLETED |
NEEDS_USER_ACTION | FAILED | EXPIRED`

Provider timeout、連線中斷或模糊回覆進入 `NEEDS_RECONCILIATION`，對帳完成前不得建立下一個
相同外部操作。

## 5. 逐輪計畫

### B0：衝突稽核、資源協調與決策 freeze

- 讀 active index、current decisions、Calendar／Travel 最新 status、handoff 與 dirty baseline。
- 執行 `git status --short`；禁止任何 `git diff`。
- 取得 repository coordinator source claim，建立 dependency／ownership／resource matrix。
- 完成並登記本計畫、SOL prompt 與 Booking 專用 skill。
- 核對 CPU、RAM、Docker 與既有 Maven／服務 consumer；不啟動重型工作。
- 產生 B1 精確 source allowlist 與禁止清單。

出口：文件與 skill 驗證通過、共享 owner 無衝突、claims 已釋放、B1 allowlist 可獨立執行。

#### B0 實際協調基線（2026-07-25）

狀態：`PASS`。文件／skill verifier、owner manifest 與 release receipt 均已驗證；B1 仍須另取
新的 source claim，不能沿用 B0 receipt。

Dirty baseline 已以 `git status --short` 留存，包含 Calendar v2、Travel 3B-A、Knowledge、
StoredMedia、LifeRecord、Intent、Notification、Project binding、V66–V81 migration 與既有 tests
的大量修改／未追蹤檔。Booking 本輪不取得這些檔案的 ownership，不執行 diff、reset、checkout、
stash、clean、搬移、還原或全域格式化。

| 依賴／資源 | 可驗證現況與 owner | B0／B1 邊界 |
| --- | --- | --- |
| Calendar Wheel 8 | active index 為 in progress；typed materialization focused gate 28/28，尚無穩定 full-regression handoff | B0 只讀；B1 不接 Calendar |
| Calendar Wheel 10 | recurrence contract 尚未通過 | 只阻擋 recurring ownership propagation；B1 不接 recurrence |
| Travel 3B-A | focused lifecycle／ownership／RLS gates 已綠，完整回歸仍待 | B0 只讀；B1 不接 Travel typed view |
| Travel 3B-B | `BLOCKED_BY_CALENDAR_WHEEL_10` | 不得把 Wheel 10 視為 Travel 自動 PASS |
| Intent／Notification／Knowledge／StoredMedia／LifeRecord | 其他 session 的 dirty baseline／共用 owner | B0、B1 全部禁止 |
| Flyway／Git index | B0 未申請；V66–V81 已存在且序列仍可能前進 | B1 不申請、不新增 migration、不 stage／commit |
| Maven target | B0 0 writer；B1 最多 1 writer且只經 `scripts/mvn-safe.ps1` | B0 不執行 Maven；B1 只跑 focused deterministic gate |
| Docker／shared runtime／browser／sandbox E2E | Docker daemon 存在但 0 container；B0 不取得任何重型資源 | B1 不使用；不得停止或重設其他 session 服務 |
| Booking source | B0 operations `booking-b0-20260725-a71f`、`booking-b0-final-20260725-c42e` | exclusive worktree source claims；均以 owner PID／manifest 驗證並釋放 |

主機盤點：22 logical processors；盤點時未見 Java／Maven process；可用記憶體約 2,554 MB；
Docker 查詢為 0 container。`dev-doctor.ps1 -Json -NoExternalProbe` 回傳 `DEGRADED`／內部 exit code
10，表示同一登入的 Global mutex prototype 已驗證、跨登入可見性未驗證。因此不做 takeover，
只接受本輪新取得且 owner PID／operation manifest 可驗證的 claim。

#### B1 精確 source allowlist

B1 只能新增下列路徑，不得修改任何既有 production 或 test 檔案：

- `src/main/java/com/aproject/aidriven/mymobilesecretary/booking/domain/**`
- `src/main/java/com/aproject/aidriven/mymobilesecretary/booking/provider/spi/**`
- `src/test/java/com/aproject/aidriven/mymobilesecretary/booking/domain/**`
- `src/test/java/com/aproject/aidriven/mymobilesecretary/booking/provider/spi/**`
- `src/test/java/com/aproject/aidriven/mymobilesecretary/booking/provider/fake/**`
- 本文件的 B1 實際 gate evidence 區段

B1 明確禁止：

- `api/**`、`calendar/**`、`travel/**`、`project/**`、`intent/**`、`reminder/**`、
  `integration/notification/**`、`knowledge/**`、`media/**`、`planning/**`、`planner/**`。
- `src/main/resources/**`、所有 Flyway migration、capability catalog、active index、current decisions、
  architecture、共用 outbox／runtime／tooling scripts 與既有 tests。
- Spring component、JPA entity／repository、Web/API、provider SDK／網路、Docker、Playwright、
  hosted checkout、sandbox 或 live side effect。

B1 resource claim 預算固定為：1 個 mutating root、0–1 個唯讀 subagent、1 個 exclusive worktree
source claim、0 個 Git index／Flyway／Docker／runtime／browser／sandbox claim；focused Maven gate
由 `mvn-safe.ps1` 單一 writer 串行取得。若 source owner／manifest 不可驗證，或 Calendar／Travel
開始占用上述 Booking allowlist，B1 必須 `BLOCKED`。

#### B0 gate evidence 與 handoff（2026-07-25）

- 文件／skill 結構 verifier：exit 0，12 passed、0 failed；確認 plan registration、唯一契約、
  B0 gate、dependency matrix、B1 allowlist／禁止清單及三份 skill reference。
- Coordinator：兩筆 exclusive worktree source operation 的 owner PID 與 `ACTIVE` manifest 均在修改前
  驗證；修改後 manifest 為 `RELEASED`、receipt outcome 為 `READY`、disposition 為 `released`、
  cleanup 為 `none`。未取得 Git index、Flyway、Maven、Docker、runtime、browser 或 sandbox claim。
- External environment：`NONE`；沒有 provider credential、traveller data、sandbox／live request、
  hosted checkout、Playwright 或付費服務。
- Mutation／idempotency／RLS／privacy／quoted context／progress／latency：外部 mutation 0，
  booking command 0；本輪純文件，不適用 idempotency、RLS、quoted context 與 latency runtime gate；
  文件與 receipt 未保存 secret、個資、raw provider payload 或 browser state。
- 未執行：Maven、Testcontainers、full regression、Duffel E2E、Playwright、shared runtime；
  這些不是 B0 gate，亦不得以本輪 PASS 宣稱任何 Booking runtime 或 provider lifecycle 已驗證。

```json
{
  "plan": "booking-commerce-sol-medium-development-test-plan",
  "phase": "B0",
  "status": "PASS",
  "approvedDecisions": [
    "B1 is isolated Booking domain, provider-neutral SPI, fake and deterministic tests only",
    "Calendar Wheel 10 blocks recurring propagation only"
  ],
  "modifiedFiles": [
    "docs/exec-plans/active/booking-commerce-sol-medium-development-test-plan.md"
  ],
  "migrationReservations": [],
  "tests": [
    {
      "command": "PowerShell Booking B0 document/skill structural verifier",
      "passed": 12,
      "failed": 0,
      "errors": 0,
      "skipped": 0,
      "exitCode": 0
    }
  ],
  "externalEnvironment": "NONE",
  "sideEffectState": "NO_EXTERNAL_MUTATION",
  "claimsReleased": [
    "booking-b0-20260725-a71f",
    "booking-b0-final-20260725-c42e"
  ],
  "failedOrSkippedGates": [
    "Maven/runtime/provider gates are intentionally not B0 gates"
  ],
  "remainingWork": [
    "Execute B1 only within the recorded allowlist under a new source claim"
  ],
  "nextAction": "Acquire B1 source claim and begin red-first deterministic Booking domain/SPI tests",
  "risks": [
    "Calendar Wheel 8 and Travel 3B-A still lack stable full-regression handoff",
    "Only about 2554 MB memory was available during B0 host inspection"
  ],
  "userDecisionRequired": [],
  "cleanupDisposition": "none"
}
```

### B1：純 Booking domain、SPI 與 fake provider

只新增 `booking/domain`、provider-neutral SPI、fake 與 tests；不建 DB、不新增 migration、不碰
Travel／Calendar／Intent／Notification／LifeRecord。覆蓋 quote expiry、價格／條款變動、庫存消失、
授權越界、部分成功、unknown result 與 replay。出口為 deterministic tests 全綠與 SPI freeze。

#### B1 gate evidence 與 handoff（2026-07-25）

狀態：`PASS`。SPI 已 freeze 為 `searchAvailability`、`refreshQuote`、`hold`、`book`、`getStatus`、
`proposeChange`、`cancel`、`reconcileUnknownResult` 八個 provider-neutral operations；B2 如需改變
這些語意必須先回到 B1 gate，不得由 persistence adapter 暗改。

實際變更只在 B1 allowlist：

- `booking/domain`：15 files，包含 capability／environment、fresh quote／material change、
  actor/workspace-bound authorization、confirmation/substitution policy、BookingPlan／Attempt／Order
  與固定狀態機。
- `booking/provider/spi`：11 files，包含八方法 contract、typed request/result 與
  `NEEDS_RECONCILIATION`。
- `booking` tests：6 files，包含 3 個 domain tests、stateful fake、fake behavior 與 provider test。
- 本 active plan 的 B1 evidence；沒有修改其他 production／test／resource 檔。

Gate 證據：

- Red：`mvn-safe.ps1 -Dspotless.check.skip=true
  -Dtest=OfferSnapshotTest,PurchaseAuthorizationTest,BookingPlanTest,FakeBookingProviderTest test`
  exit 1，停在 `testCompile`，原因為 B1 domain／SPI／fake 尚不存在；這是有效實作前紅燈。
- Scoped imports：`mvn-safe.ps1 '-DspotlessFiles=.*booking.*[.]java' spotless:check`
  exit 0，0 test、0 skipped。全域 Spotless 仍受他線
  `CalendarKnowledgeMaterializationService` dirty baseline 影響，未修改該檔。
- Green：相同四類 focused tests，process-local Java 21，`-Dspotless.check.skip=true`，
  exit 0，11 passed、0 failed、0 errors、0 skipped，耗時 84.1 秒。
- Static boundary verifier：32 Booking files、0 個 Spring／JPA／network／system-clock／sleep
  forbidden references；`BookingProvider` 八個 SPI method 全數存在。Base revision
  `d696efb8c9561f8d62110af78243f854b6c330db`。

驗證語意：quote 在 expiry 時刻即 stale；價格、幣別、條款、庫存、旅客、provider、environment、
capability 與不可退接受均為 material authorization boundary；workspace／actor 不符在 mutation
前 fail closed。部分成功後狀態為 `PARTIALLY_COMPLETED`、保留成功 order 並停止後續。
unknown-after-send 的首次送出、相同 command replay 與 reconcile 合計 external mutation 恰為 1；
unsupported capability 為 0 mutation。所有時間由注入 `Clock` 決定。

External environment 為 `FAKE`；provider credential、traveller 原文、sandbox／live request、
Docker、Playwright、payment、hosted checkout、DB、RLS 或 migration 均未使用。B1 不含 persistence，
所以 RLS／crash-restart／outbox 是 B2 gate；不含 conversation，所以 quoted context／latency／holdout
是 B6 gate。Focused tests 不代表 release 或 Duffel lifecycle 完成。

```json
{
  "plan": "booking-commerce-sol-medium-development-test-plan",
  "phase": "B1",
  "status": "PASS",
  "baseRevision": "d696efb8c9561f8d62110af78243f854b6c330db",
  "sourceSummary": {
    "bookingDomainFiles": 15,
    "providerSpiFiles": 11,
    "bookingTestFiles": 6,
    "totalBookingFiles": 32
  },
  "tests": [
    {
      "gate": "red",
      "exitCode": 1,
      "result": "expected testCompile failure before implementation"
    },
    {
      "gate": "booking-scoped-spotless",
      "exitCode": 0,
      "passed": 0,
      "failed": 0,
      "errors": 0,
      "skipped": 0
    },
    {
      "gate": "focused-domain-spi-fake",
      "exitCode": 0,
      "passed": 11,
      "failed": 0,
      "errors": 0,
      "skipped": 0
    }
  ],
  "externalEnvironment": "FAKE",
  "sideEffectState": "FAKE_ONLY_NO_EXTERNAL_MUTATION",
  "mutationEvidence": {
    "unknownReplayReconcileExternalMutations": 1,
    "unsupportedCapabilityExternalMutations": 0
  },
  "claimsReleased": [
    "booking-b1-20260725-e93d",
    "booking-b1-impl-20260725-b82c",
    "booking-b1-impl2-20260725-d17f",
    "booking-b1-final-20260725-a28e",
    "wrapper-owned Maven and scoped Spotless claims"
  ],
  "failedOrSkippedGates": [
    "repository-wide Spotless blocked by foreign Calendar dirty baseline",
    "full regression is not a B1 exit gate",
    "RLS, sandbox, conversation and live gates belong to later phases"
  ],
  "remainingWork": [
    "B2 persistence, RLS and durable execution under a new claim",
    "Recheck and reserve the then-current Flyway version only inside B2"
  ],
  "nextAction": "Run B2 conflict audit and acquire source plus Flyway reservation in canonical order",
  "risks": [
    "Calendar Wheel 8 and Travel 3B-A stable full-regression handoffs are still pending",
    "Global Spotless cannot be used as evidence until foreign dirty imports are resolved"
  ],
  "userDecisionRequired": [],
  "cleanupDisposition": "none"
}
```

### B2：Persistence、RLS、durable execution

當輪才預約下一個 Flyway version。建立 workspace／actor-owned quote、authorization、plan、order、
attempt、webhook inbox／outbox；完成 composite ownership、RLS、注入 `Clock`、crash/restart、
lease recovery、unknown reconciliation 與 exactly-once-visible terminal result。

#### B2 gate evidence 與 handoff（2026-07-26）

狀態：`PASS`。Calendar W9-A stable handoff 為 `PASS` 後，重新查核實際 latest migration 為 V83；
Booking 自行取得 `worktree/source` 與 `repo/flyway-sequence/main/V84` exclusive claim，獨立
contender 回 `BUSY`，沒有沿用或接收 Calendar claim transfer。

V84 建立七張 actor-owned durable tables：

- `booking_offer_snapshot`
- `booking_purchase_authorization`
- `booking_plan`
- `external_booking_order`
- `booking_attempt`
- `booking_webhook_inbox`
- `booking_execution_outbox`

所有表都有 workspace／actor ownership、composite owner foreign key、`ENABLE`＋`FORCE ROW LEVEL
SECURITY` 與 actor-scoped policy。DB 只保存 typed quote／authorization／state、masked provider
reference、event-key digest 與 payload digest；不保存 raw provider payload、付款資料、credential、
OTP／3DS／CAPTCHA、browser state 或旅客原文。

`BookingExecutionStore` 使用注入 `Clock` 與固定參數綁定 SQL，完成：

- quote／authorization／plan 的 durable round-trip 與 conflicting replay fail closed；
- 每個 intended operation 的 actor-scoped unique key、concurrent duplicate 仲裁與 lease fencing；
- pre-send expired lease 可安全 recovery；`DISPATCHED` 後一律
  `NEEDS_RECONCILIATION`，禁止重送；
- reconcile success、failure 與 partial success；成功 order 保留，partial 後不繼續；
- duplicate／conflicting／out-of-order webhook digest inbox；
- stable terminal event key、unique outbox row、`SKIP LOCKED` claim、token-fenced ack 與 retry lease。

實際 gate：

- Red-first：focused command exit 1，停在 `testCompile`，唯一首要原因為
  `BookingExecutionStore` 尚不存在。
- Scoped Spotless 與完整 `test-compile`：exit 0。
- B2＋database architecture＋Calendar neighbor focused gate：18 tests、0 failure、0 error、
  0 skipped。新 JDBC store 的 SQL 結構皆為固定 text block；workspace、actor、ID、provider、
  digest、狀態與 limit 全使用參數綁定，已精準加入 reviewed low-level access allowlist。
- 第一次 root regression：1,434 tests、1 failure、1 error、18 skipped；兩筆均為 Calendar W9-A
  forward-compatibility test debt。migration oracle 把 latest 鎖死 V83；舊 RLS fixture 未填 V83
  新增的 source owner。只修正 Calendar tests，不修改 Calendar production 或 V83。
- Neighbor 修正後 root regression：1,434 tests、0 failure、0 error、18 skipped。
- 加入 authorization／plan conflicting replay gate 後最終 root regression：1,435 tests、
  0 failure、0 error、18 skipped，耗時 403.8 秒。

External environment 為 `FAKE`；外部 provider request、book／hold／pay／cancel、真實個資、
Docker sandbox E2E、Playwright、hosted checkout 與 live side effect 均為 0。Testcontainers 只用於
本機 PostgreSQL migration／RLS gate。B2 不宣稱 Duffel test-mode 或 production transaction 已驗證。

```json
{
  "plan": "booking-commerce-sol-medium-development-test-plan",
  "phase": "B2",
  "status": "PASS",
  "actualFlywayLatest": "V84",
  "migration": "V84__create_booking_durable_execution.sql",
  "coordinatorOperation": "booking-b2-20260726-v84",
  "sourceSummary": {
    "production": [
      "booking/persistence/BookingExecutionStore.java",
      "db/migration/V84__create_booking_durable_execution.sql"
    ],
    "tests": [
      "booking/persistence/BookingDurableExecutionIntegrationTest.java",
      "shared/security/DatabaseAccessSafetyArchitectureTest.java",
      "calendar/adoption/CalendarSharedAdoptionMigrationTest.java",
      "calendar/CalendarRlsIntegrationTest.java"
    ]
  },
  "tests": [
    {
      "gate": "red-first",
      "exitCode": 1,
      "result": "expected missing BookingExecutionStore testCompile failure"
    },
    {
      "gate": "focused-b2-architecture-neighbor",
      "exitCode": 0,
      "passed": 18,
      "failed": 0,
      "errors": 0,
      "skipped": 0
    },
    {
      "gate": "final-root-regression",
      "exitCode": 0,
      "passed": 1435,
      "failed": 0,
      "errors": 0,
      "skipped": 18,
      "durationSeconds": 403.8
    }
  ],
  "externalEnvironment": "FAKE",
  "sideEffectState": "LOCAL_DB_ONLY_NO_EXTERNAL_MUTATION",
  "mutationEvidence": {
    "providerMutations": 0,
    "concurrentDuplicateAttempts": 1,
    "postDispatchResends": 0,
    "terminalOutboxRowsPerPlan": 1
  },
  "claimsReleased": [
    "booking-b2-20260726-v84 worktree/source",
    "booking-b2-20260726-v84 repo/flyway-sequence/main/V84",
    "wrapper-owned Maven target claims"
  ],
  "failedOrSkippedGates": [
    "Duffel test mode belongs to B5",
    "conversation, privacy and latency gates belong to B6",
    "live transaction and Playwright gates were not authorized"
  ],
  "remainingWork": [
    "B3 read-only availability orchestration",
    "B4 fake purchase orchestration",
    "B5-B9 provider, conversation, monitoring, fallback and release gates"
  ],
  "nextAction": "Allow Calendar W9-B to recheck and reclaim source/Flyway; Booking later reacquires for B3",
  "risks": [
    "Exactly-once-visible delivery still requires downstream consumers to deduplicate by stable event key",
    "No external provider contract or sandbox lifecycle has been verified in B2"
  ],
  "userDecisionRequired": [],
  "cleanupDisposition": "none"
}
```

#### Checkpoint B 發布前重新驗證（2026-07-28）

Calendar Wheel 8 PASS 與 Travel 3B-A stable handoff 已由 Calendar lane 再確認足以滿足 B0–B2
dependency；W9-E 不回溯阻擋 B2。Calendar 在 focused safe gate 釋放
`calendar-w9e-20260728-v88-r18` 的 source／V88 claims 後，Booking 自行重新查核 owner、PID、
manifest 與 Flyway sequence，沒有 claim transfer。

Booking 以 `booking-checkpoint-b-20260728-v84-r2` 原子取得 Testcontainers capacity、V84
publication guard、repo git-common、worktree Git index 與 source claims。發布前基線為
`714a62037866f703d0811fd3469badec2a8bd055`，重新 fetch 後 `origin/main` 為
`99882c47d66a0018e3cc6b259de4efbd015d9c53`；本機 migration sequence 為 V83、V84、V85、
V86、V87、V88，V84 檔名與內容未重新編號或取代。

- Booking-scoped Spotless：exit 0，0 tests、0 skipped，耗時 11.5 秒；沒有格式化 Calendar W9-E
  或其他 session 的 shared dirty files。
- B0–B2 publication focused gate：
  `OfferSnapshotTest,PurchaseAuthorizationTest,BookingPlanTest,FakeBookingProviderTest,`
  `BookingDurableExecutionIntegrationTest,DatabaseAccessSafetyArchitectureTest,`
  `CalendarSharedAdoptionMigrationTest,CalendarRlsIntegrationTest`，exit 0，
  29 tests、0 failure、0 error、0 skipped，耗時 152.2 秒。
- Root full regression：`scripts/mvn-safe.ps1 test`，exit 0，1,550 tests、0 failure、0 error、
  18 skipped，耗時 836.8 秒。Surefire 目錄另保留 2026-07-20 的
  `QuietMavenTemporaryFailureTest` 故意失敗舊報告；依不預設 clean 原則保留，並以本輪
  2026-07-28 11:00 後 371 份 XML 聚合再次確認 1,550／0／0／18。

External environment 維持 `FAKE`／`LOCAL_DB_ONLY`；Testcontainers 只使用本機 PostgreSQL。
Provider、sandbox、live、Playwright、hosted checkout、credential、真實旅客／付款與外部 mutation
全部為 0。此證據只代表產品 commit 可進 PR review；產品 PR 與後續 state-only handoff 尚未合併
前，`TR-DESKTOP-B3-START` 仍不得標成 READY。

#### Integration review remediation（2026-07-28）

Integration 對 PR #1 head `657119f226a0c6826e7d976f0434b2b8ad13af4b` 回覆
`CHANGES_REQUIRED`。Calendar 先完成 W9-E，將 validated V88 與 22 個直接耦合檔提交為
`7ab1407ec1e30d5b470bdb9774680a5ff9cb577b`；V88 的 HEAD／validated worktree blob 均為
`300c58c0dca79f44497a7d7f72c62923d68f914c`。Calendar focused 19／19、
security-neighbor 137／137、root 1,550／0／0／18 通過並釋放 source、V88、Git、Docker、
Maven claims 後，Booking 才以 `booking-b2-review-20260728-v84-r1` 自行原子取得
Testcontainers capacity、V84 publication guard 與 source；沒有 claim transfer。

Booking 只修正 review 指定的四類 safety blocker，沒有修改 V84、V88 或 Calendar：

- `CancellationAuthorization` 是獨立於 purchase authorization 的短效 grant，綁定
  workspace、actor、provider、environment 與 exact order；到期當刻即拒絕。
- fake provider 的 operation replay cache 綁定 SHA-256 command semantic digest；跨 actor、
  workspace、offer 或 target 的相同 operation ID fail closed，不回傳舊 order，外部 mutation
  count 不增加。取消成功保留原 order ID／provider reference，只改為 `CANCELLED`。
- `markDispatched` 與 terminal ACK 在同一 SQL transition 同時驗 owner、token、state 與未過期
  lease；過期前後及重新 claim 後的舊 token 均無法 transition。
- durable success 先回查 plan → purchase authorization → offer 的 provider/environment
  boundary；既存 order 必須與 plan、order ID、provider、environment、provider reference、
  status、observed time 全語意一致，conflicting replay fail closed 且不新增 row。

實際 gate：

- Red-first focused：exit 1，停在 `testCompile`，唯一首要原因為尚無
  `CancellationAuthorization`。
- Booking scoped Spotless：`-DspotlessFiles=.*booking.*[.]java spotless:check`，exit 0。
- B0–B2 focused/security-neighbor：
  `OfferSnapshotTest,PurchaseAuthorizationTest,CancellationAuthorizationTest,BookingPlanTest,`
  `FakeBookingProviderTest,BookingDurableExecutionIntegrationTest,`
  `DatabaseAccessSafetyArchitectureTest,CalendarSharedAdoptionMigrationTest,`
  `CalendarRlsIntegrationTest`，exit 0，35 tests、0 failure、0 error、0 skipped，89.1 秒。
- Root full regression：`scripts/mvn-safe.ps1 -Dspotless.check.skip=true test`，exit 0，
  1,556 tests、0 failure、0 error、18 skipped，361.3 秒。

External environment 維持 `FAKE`／`LOCAL_DB_ONLY`；沒有 provider request、sandbox／live、
Playwright、hosted checkout、credential、真實旅客／付款或外部 mutation。PR 仍為 draft；
本段 PASS 只允許刷新產品 PR head 並要求 Integration re-review，不發布 Checkpoint B，不建立
state-only PR，也不開啟 B3。

```json
{
  "eventId": "BOOKING-B2-INTEGRATION-REVIEW-REMEDIATION",
  "phase": "B2",
  "status": "PASS_AWAITING_INTEGRATION_REREVIEW",
  "reviewedHead": "657119f226a0c6826e7d976f0434b2b8ad13af4b",
  "calendarHead": "7ab1407ec1e30d5b470bdb9774680a5ff9cb577b",
  "migration": {
    "booking": "V84",
    "modified": false,
    "calendarV88ModifiedByBooking": false
  },
  "tests": {
    "focused": {
      "exitCode": 0,
      "tests": 35,
      "failures": 0,
      "errors": 0,
      "skipped": 0,
      "durationSeconds": 89.1
    },
    "fullRegression": {
      "exitCode": 0,
      "tests": 1556,
      "failures": 0,
      "errors": 0,
      "skipped": 18,
      "durationSeconds": 361.3
    }
  },
  "externalEnvironment": "FAKE_LOCAL_DB_ONLY",
  "externalMutationCount": 0,
  "checkpointB": "BLOCKED_UNTIL_PRODUCT_AND_STATE_ONLY_MERGED",
  "desktopStart": "BLOCKED"
}
```

#### Integration final remediation（2026-07-28）

Integration 對 PR #1 head `cb95d6f3ee281fdf7b29bc4565f55e5ce3645218` 的三路 native
re-review 已收斂：conflicting replay semantic digest、lease fencing、terminal authorization／
full equality、scope／history、V84／V88 blobs、claims receipt 與 GitHub mergeability 均 PASS；
唯一剩餘 blocker 是 fake cancel adapter 未把 request 綁回目前 provider instance。

Booking 以 `booking-b2-final-review-20260728-v84-r2` 原子取得 Testcontainers capacity、V84
publication guard 與 source，且只修改 fake provider 與其測試。新增 provider misroute 與
environment misroute 兩個 zero-mutation 測試；紅測為 7 tests 中 2 failure，兩者實際都錯誤回傳
`SUCCEEDED`。修正後 cancel 在查詢 replay 或送出前驗證 order provider 必須等於目前 adapter
instance，且 environment 必須為 `FAKE`；不符即回傳 `provider-boundary-mismatch`，不洩漏 order、
不覆寫 replay cache，也不增加 external mutation count。

實際 gate：

- Fake provider red-first：exit 1，7 tests、2 failure、0 error、0 skipped；失敗案例正好為
  provider misroute 與 environment misroute。
- Fake provider green：exit 0，7 tests、0 failure、0 error、0 skipped，28.2 秒。
- Booking scoped Spotless：`-DspotlessFiles=.*booking.*[.]java spotless:check`，exit 0，
  0 tests、0 skipped，4.4 秒。
- B0–B2 focused/security-neighbor：`OfferSnapshotTest,PurchaseAuthorizationTest,`
  `CancellationAuthorizationTest,BookingPlanTest,FakeBookingProviderTest,`
  `BookingDurableExecutionIntegrationTest,DatabaseAccessSafetyArchitectureTest,`
  `CalendarSharedAdoptionMigrationTest,CalendarRlsIntegrationTest`，exit 0，37 tests、
  0 failure、0 error、0 skipped，70.1 秒。
- Root full regression：`scripts/mvn-safe.ps1 -Dspotless.check.skip=true test`，exit 0，
  1,558 tests、0 failure、0 error、18 skipped，343.8 秒。

External environment 維持 `FAKE`／`LOCAL_DB_ONLY`；provider、sandbox／live、Playwright、
hosted checkout 與外部 mutation 仍為 0。V84、V88 與 Calendar 檔案均未修改。此 PASS 只允許
刷新同一 draft PR head 並要求 Integration 最終複核；不得 mark ready／merge、不得建立
state-only handoff、不得開始 B3。

```json
{
  "eventId": "BOOKING-B2-INTEGRATION-FINAL-REMEDIATION",
  "phase": "B2",
  "status": "PASS_AWAITING_INTEGRATION_FINAL_REVIEW",
  "reviewedHead": "cb95d6f3ee281fdf7b29bc4565f55e5ce3645218",
  "migration": {
    "booking": "V84",
    "modified": false,
    "calendarV88ModifiedByBooking": false
  },
  "tests": {
    "focused": {
      "exitCode": 0,
      "tests": 37,
      "failures": 0,
      "errors": 0,
      "skipped": 0,
      "durationSeconds": 70.1
    },
    "fullRegression": {
      "exitCode": 0,
      "tests": 1558,
      "failures": 0,
      "errors": 0,
      "skipped": 18,
      "durationSeconds": 343.8
    }
  },
  "externalEnvironment": "FAKE_LOCAL_DB_ONLY",
  "externalMutationCount": 0,
  "checkpointB": "BLOCKED_UNTIL_PRODUCT_AND_STATE_ONLY_MERGED",
  "desktopStart": "BLOCKED"
}
```

### B3：Availability orchestration（分三個獨立 gate）

#### B3-Core：純 Booking transient core

從已發布的 B2 handoff 建立桌電 branch，只在 `booking/**` 實作 provider-neutral search
orchestration、deterministic ranking、transient progress／terminal ports 與 tests。最多同時查兩個
provider source；單一來源失敗不得抹掉其他可信結果。先排除 unavailable、expired、IMPOSSIBLE；
unknown fee 不得標成最低價。最低總價只比較 fully-known total；最彈性比較退改風險；最佳平衡
依 feasibility、unknown／必要加購、風險、價格排序。同一候選跨多 category 只回一筆、多 badge。

此 gate 零 DB、migration、Spring controller、Calendar／Travel 修改、Intent、LINE、provider
network 與外部 mutation；完成後只能宣稱 B3-Core，不得宣稱 durable progress 或完整 B3。

##### B3-Core gate evidence（2026-07-30）

狀態：`PASS_AWAITING_PR_REVIEW`。桌電從 dependency-closure handoff
`6219edcc9d6da08a410ebe38160c253316977d5b`、`origin/main`
`cbea39699d368104a60b5298af4201e417976456` 建立並更新
`desktop/booking-b3-core`。只新增 `booking/availability/**` 純 Java core 與 tests，沒有修改
provider SPI、B2 durable execution、Calendar、Travel、Intent、LINE、migration、shared config
或 tooling。

實作涵蓋最多兩個 source 的 bounded concurrency、單一 source failure／timeout／invalid data
隔離、固定 `Clock` 的 stale／expired／`IMPOSSIBLE` 過濾、fully-known total 的最低總價、
refund／change risk 的最彈性、feasibility／unknown fee／必要加購／風險／價格的最佳平衡、
deterministic tie-break、跨 category 多 badge 去重，以及 transient progress／exactly-one
terminal sink。不同 currency 無換匯證據時不產生虛假的 cheapest badge。

正式 gate 使用 Temurin JDK 21：

- Scoped Spotless：exit 0，0 tests、0 skipped。
- B3 focused `AvailabilitySearchOrchestratorTest`：exit 0，12 tests、0 failure、0 error、
  0 skipped。
- Booking／security-neighbor focused：exit 0，31 tests、0 failure、0 error、0 skipped。
- Root regression：exit 0，1,518 tests、0 failure、0 error、16 skipped，357.0 秒。

External environment 為 `NONE`；provider request、sandbox／live、credential、Playwright、真實個資、
hold／book／pay／cancel 與 external mutation 全部為 0。此 PASS 只代表 provider-neutral transient
B3-Core；不宣稱 durable progress、可信即時庫存、完整 B3、B4 fake purchase或任何 production
transaction lifecycle。

#### B3-Durable：Booking-owned search durability

Calendar Wheel 10 完成且筆電發出一次性 schema handoff 後，才新增當時實際下一版 migration。
只建立 Booking-prefixed search job、candidate／offer binding、progress 與 exactly-one terminal
outbox；必須有 workspace／actor RLS、application filter、Clock、idempotency。未選取 search
job／candidate 保留 7 天後刪除；被 authorization／execution 引用的 offer 依交易稽核保存。
不得保存 raw provider payload、credential、旅客原文或付款資料，不得改上游 table 或既有 migration。

#### B3-Upstream Adapters：Travel typed handoff

Travel Wheels 6–7 的 transport／stay／traveller typed view PASS 後才接入。Adapter 只放 Booking
boundary，不修改 Calendar／Travel／Project。B3-Core、B3-Durable、Upstream Adapters 都通過後，
才可宣稱完整 B3。

### B4：授權與 fake purchase orchestration

在 B3-Core 後、B3-Durable 前即可使用 V84 與既有 execution store 實作。覆蓋三種確認模式 ×
三種替代強度；送單前重新驗證 quote／authorization，claim、mark dispatched、呼叫 fake、
settle／reconcile。價格、currency、條款、旅客、時間、地點或 provider 超出授權即失效。
timeout／crash after send 進 `NEEDS_RECONCILIATION`，禁止盲目重送；partial 停止並保留成功項，
只產生 cancel／replacement proposal，不自動取消。每條測試斷言 fake mutation count 與
`Environment=FAKE`。

雙機第一波外部邊界到本 gate 為止：零新 migration、零公開 API／LINE、零 Duffel／sandbox／live
provider、零 Playwright、零 credential、零真實訂單或付款。

### B5：Duffel Flights／Stays test-mode adapter

使用 test token、Duffel Airways 與 Test Hotels；每筆斷言 `live_mode=false`。完成 flight offer/order、
stay search/rate/quote/booking、3DS、decline、hold、cancel 與 webhook replay。沒有 test credential
時標示 BLOCKED，不用 Playwright 規避。

### B6：LINE、對話與安全工作頁

Commands 穩定後才新增 Intent、handler、capability catalog 與 regression。LINE 顯示方案、授權摘要、
durable progress 與 terminal result。卡片、3DS、OTP、CAPTCHA、登入使用短效 actor-scoped HTTPS
工作頁或 provider hosted UI。套用 conversation skill 的 quoted context、privacy、latency 與 holdout。

### B7：訂後監控與異動提案

Webhook 驗簽、去重、亂序與版本前進。延誤、取消、降價與異動只建立 impact proposal；新授權前
不改外部訂單。成功訂購、取消與重要異動以裁切後事件接 LifeRecord／tag graph。

### B8：Playwright 白名單備援

只在 API／官方 checkout 均不能滿足且條款允許時啟用。一次一個隔離 browser context，不和 Maven／
Docker 重型 gate 並行。先對自建 mock ticket site 驗證；每站有版本、DOM contract、kill switch、
人工接手。遇 CAPTCHA、OTP、3DS、價格／條款／DOM 不符立即停下。

### B9：Release 與可選 live pilot

Release 必須通過 deterministic、RLS、conversation、privacy、latency、full regression 與 Duffel test E2E。
Production 先做 quota 保護的 read-only smoke。真實 canary 不是 release blocker；日後需使用者當輪
批准 provider、金額與不可回收成本。第一筆優先選明示全額退款且期限充足的住宿。Provider 顯示
cancelled／refund accepted 不等於退款入帳，原付款方式入帳前維持 `REFUND_PENDING`。

## 6. 主機資源與多 Session 協調

主機資源有限，預設同一時間只有一個 mutating root agent、一個 Maven writer、一組 Testcontainers／
Docker gate、一個 Playwright context 與一個 sandbox E2E。完整 Maven regression、sandbox E2E、
Playwright 不互相並行。

所有資源一次宣告並由 repository coordinator canonical rank 排序取得，不自行反序拿鎖：

| Resource | Mode | 規則 |
| --- | --- | --- |
| repo Git／Flyway sequence | exclusive | migration 當輪才預約 |
| worktree source／Git index | exclusive | 精確 allowlist；未授權不 commit |
| Maven target | single writer | 只經 `scripts/mvn-safe.ps1` |
| Docker capacity | capacity 1 | Testcontainers serial |
| shared runtime／LINE／ngrok | shared read；mutation exclusive | 不停止其他 session |
| DB／Redis／Flyway history | retain-only | 禁止 clean、reset、FLUSHALL |

Timeout／heartbeat 過期不能授權 takeover。Owner、generation、receipt 或 process 無法驗證時 BLOCKED。
跨機器 gate 另依 trigger registry：local PASS、commit、push 或 draft PR 不解鎖下一輪；PR ready
與 merge 都需發對應 receipt，`HARD_YIELD` 後不得順手開始下一個 Booking／ADD gate。

## 7. Subagent 政策

預設關閉 mutating subagent，共享 worktree 只允許唯讀研究。可在 SPI freeze、獨立 worktree、
非重疊 source claim 且資源允許後，交付單一 provider adapter、mock site 或獨立 security／scenario
評估。禁止 subagent 修改 migration、共用文件、capability catalog，執行 live side effect，或再 spawn。

最多主 agent＋三個 bounded subagent；受限主機預設只啟動一個唯讀 subagent。主 agent 獨占整合、
migration、共用文件與完整回歸。

## 8. Context 壓縮與 handoff

候選點為 B0、B1、B2、B3、B4、B5、B7、B9 通過出口後，但不是強制時刻。Migration／交易中途、
未落盤決策、未定位測試失敗、provider unknown／pending／partial、live refund 未 terminal 或仍持有
claim 時不得建議壓縮。

適合時輸出「現在是適合壓縮 context 的時機」及 handoff，至少包含 plan、phase、status、
base revision、dirty baseline、決策、不變量、修改檔案、resource owner／generation、migration、
tests、external environment、masked evidence、side-effect state、failed/skipped gates、released claims、
remaining work、next action、risks、blockers、user decisions 與 cleanup。禁止保存 secrets、cookies、
storage state、旅客個資、完整訂單號或 raw provider payload。

## 9. 測試與完成宣稱

1. Domain／property tests，固定 `Clock`。
2. WireMock／provider fixture contract。
3. Stateful fake crash、restart、outbox 與 reconciliation。
4. Conversation actual-entry、RLS、privacy、latency 與 sealed holdout。
5. Duffel test mode Flights／Stays golden flow；CI 不接受 live token。
6. Production read-only smoke。
7. 可選 live canary，需另行批准且不阻擋 release。

Sandbox 通過只可宣稱「test-mode transaction lifecycle 已驗證」，不得宣稱 production 支付、退款
或第三方正式網站 Playwright 已驗證。Focused tests 不等於 release 完成。

## 10. 每輪完成報告

使用繁中回報變更檔案與使用者可見行為、測試命令及 counts、mutation／idempotency／RLS／privacy／
quoted context／latency 證據、provider environment、未測路徑、claims release、風險與下一動作，
且不得顯示 diff。

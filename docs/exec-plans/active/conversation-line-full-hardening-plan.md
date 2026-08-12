# LINE／全服務對話品質全面修復計畫

## 2026-08-12 route departure-reminder explicit lead／detail follow-up

- Actual LINE evidence：使用者在 `route.departure-reminder` 回覆「好，前五分鐘叫我」，舊路徑只以
  「好」判定接受並建立 `-30 分鐘／出發當下`兩條自適應提醒；後續「哪兩個？」被路由成一般提醒查詢，
  再補「我是問你幫我設的提醒是哪兩個？」仍成 UNKNOWN。此 production 修正永久 invalidates 先前 baseline。
- 修正 contract：明確 `出發前／提前／前 N 分鐘`加提醒動詞時，優先建立 exactly-one relative rule；未明確
  分鐘才使用既有 adaptive policy。每次成功都列出 actual scheduled time與相對出發說明，不再只公開 count。
- 完成後 reminder detail follow-up 由 active Calendar workflow與 actor-owned active rules 唯讀解析；不得要求
  使用者重述完整名稱、不得從 raw chat history找目標、不得造成 reminder／Calendar mutation。
- failure-first：23 tests中4 failures（3種明確提前量皆錯建adaptive，detail follow-up失敗）。修正後 focused
  40/40 PASS；reminder/conversation neighbor 74/74 PASS；Calendar actual-entry＋LINE webhook/replay 93/93 PASS。
  replay證明相同request只建立一條 `-5分鐘`規則與一個occurrence；三種detail follow-up連續查詢仍零mutation。

> 狀態：`LOCAL_PRODUCT_COMPLETE_RELEASE_BLOCKED`
>
> Production owner：筆電 Conversation／Intent／LINE lane
>
> 2026-08-08：route-origin／context、route reply／buffer／Maps與safe-time reschedule production hardening及
> local automated gates已完成。PR #41已交付`SCENARIO_ROUTING` benchmark、sealed holdout PASS與
> public-response evaluation PASS；兩台Automatic review的Git evidence均PASS/openCount 0。舊runtime與LINE
> receipts已因重開機失效；本版本fresh runtime／官方LINE與全新24小時／20筆本人LINE baseline完成前不得評估READY。
>
> 基底 worktree：`D:\my-project\my-mobile-secretary\var\worktrees\calendar-w11`
>
> 基底 branch：`codex/calendar-w11-cutover`
>
> 已簽章產品基底：`b32cb0d05db5feb78a5671380d3fc0836dd2b970`
>
> 上位契約：Calendar W11-H、全服務 Conversation Focus、雙機 ownership／trigger registry、

## 2026-08-10 route core interval／buffer／start-reminder follow-up

- failure-first 證明舊路徑會把一般前後緩衝擴入 provider route option與公開／持久化區間，且無 adjacency
  也會先問 general buffer；修正後核心時間只使用 provider departure／arrival，無 qualifying connection risk
  不詢問或設定一般緩衝。停車與等車維持獨立 operation，不改變 route duration。
- route 建立成功後新增共用 start-reminder單題 lifecycle；safe conflict／missing-location等高優先問題仍先處理。
  relative reminder 隨時間調整，fixed reminder重新確認並清除舊待審規則；ride-hail保留提早叫車提醒。
- focused 16/16、context／home／explicit-time actual-entry 10/10、standalone actual-entry 17/17、fixed-review
  integration 1/1 PASS。20-sample route latency hard gate保留 P95≤1,000 ms，warm P95=494 ms、cold max=1,164 ms。
- conditional connection-buffer failure-first 2/2先失敗，證明保留／安全調整都曾直接跳到提醒；修正後以
  `route.connection-buffer-keep`／`route.connection-buffer-adjust`保留已選分支，四種常用回答語法5/5、
  conversation/lifecycle/inventory 25/25、standalone actual-entry 17/17、Calendar draft actual-entry 20/20 PASS。
- safety-neighbor 42/42、REST／LINE／reminder 38/38、migration／FORCE RLS 2/2 PASS；fresh clean root
  2,176 tests、18 skipped、0 failure/error，runner execution 555.5秒。Official runtime generation
  `71fa229818634462877fd313e5c06b6b`為UP／VERIFIED_DIRTY，PostgreSQL／Redis healthy、LINE connected、
  Dispatcher DISARMED。Automatic reviewedAt `2026-08-10T10:20:06.5040303Z`、contract
  `786e05082c5e226466c84d7c`，六項MATCH、PASS/openCount 0且獨立assertion PASS。
- 尚未啟動第十九次baseline；須先由使用者透過真人LINE完成本版route lifecycle代表案例。所有舊窗口、
  generation、counts與receipts持續永久失效。
- 真人LINE揭露衝突回覆只問「要改用其他時間嗎？」且非重疊風險未量化原因；本輪新增direct-overlap、
  previous-only與next-side typed question，公開gap／required／shortage並禁止後方風險提供往後安排假選項。
  第一筆production修改後generation `71fa229818634462877fd313e5c06b6b`與reviewedAt
  `2026-08-10T10:20:06.5040303Z`永久失效；新baseline仍未啟動。測試尚因managed-stop自我競爭blocked，
  不宣稱修復已完成。
- 第十八次 baseline維持永久失效；任何新的 release baseline必須等本輪production、root、runtime、Automatic與
  官方LINE gate全部完成後重新開始，不得沿用舊generation／counts／receipts。
> `develop-and-evaluate-conversation-capability` skill

## 1. 目標與 release 結論

本計畫修復目前 LINE 對話的三個系統性問題：公開回覆仍可能帶出內部判斷／識別資訊、缺漏資訊一次
整批詢問、以及使用者已回答的資訊在後續回合被重複詢問。修復範圍是共用 Conversation contract，
不是只在 LINE controller 或單一例句補字串判斷。

本計畫完成前：

- `b32cb0d` 只能視為先前 W11-H local product stable gate，不得標示 W11 release READY。
- 所有既有24小時／20 inbound baseline均屬舊product generation且已永久失效；不得沿用startedAt、counts、
  runtime generation或receipt，只有最後production與environment gate成立後才能建立新窗口。
- 桌電仍不能修改Conversation、Intent、LINE、Calendar、Reminder、Flyway或共用wiring。桌電evidence
  coordination已READY／ACK；PR #41的benchmark、sealed holdout、public-response evaluation與Desktop
  Automatic review已進Git並通過完整性／隱私稽核。
- Local PASS、LINE 手動可回覆、commit 或 push 都不得冒充 W11 READY。

完成定義是：所有公開出口共用同一安全邊界；所有 needs-input 回覆每輪最多問一個必要問題；typed pending
state 能合併使用者已提供的資訊；所有 executable Intent 及特殊對話入口都有可執行 contract；focused、
actual-entry、RLS、replay、root、sealed holdout、LINE E2E 與全新 monitoring baseline 全綠。

## 2. 已確認的具體問題核心

### 2.1 公開回覆安全邊界放錯位置

目前 `IntentResult` 建構時執行 `UserReplySafetyPolicy`，但它是大小寫敏感的字串 denylist，且不是 channel
真正出站前的最後一道邊界。已確認的風險包括：

- `IntentService` 單一 command 的 `BusinessException` 路徑直接把 `e.getMessage()` 轉成公開澄清。
- `userFacingUnknownReason(...)` 在少數 pattern 之外直接回傳 model／router reason。
- formatter、greeting 與 focus notice 可在既有 sanitizer 之後再組合文字。
- LINE 圖片／OCR 使用 `ReceiptResult.message()` 直接建立 `PreparedReply`，繞過 `IntentResult` guard，並附加
  資料庫型檔案編號。
- LINE replay 直接回放先前保存的 response body，沒有在當次出站重新驗證。
- error fallback、viewer-role denial 與未來 async completion 由各入口自行組字串，沒有統一 contract。

因此核心不是 marker 不夠多，而是 internal diagnostic 與 public response 沒有型別分離，且 guard 位於 DTO
建構點而不是所有 adapter 共用的 final outbound boundary。

### 2.2 Production code 明確批量索取缺漏

靜態盤點目前找到至少 34 個 production files、102 個 `IntentResult.clarificationNeeded(...)` 呼叫點；
下列路徑已明確使用 missing list、requirements join 或多欄位通用問句：

- `ConditionalRecurrenceConversationService`
- `ConditionalVenueConversationService`
- `MonthlyOrdinalRecurrenceConversationService`
- `SchoolTransportConversationService`
- `TaskMutationIntentHandler`
- `IntentHandlerExceptionMapper`
- `IntentService` UNKNOWN／AI-unavailable fallback

其中條件式週期、條件式場地與接送會建立 `List<String> missing`，再用 `String.join(...)` 或編號清單一次
詢問。這是 deterministic Java 行為，不是單次模型失常。

### 2.3 跨輪上下文不是 typed slot state

`ConversationContextService` 目前保存最後一輪 user／assistant text、最後 action、最近 Task／Schedule／Place
與候選清單；它不等於可恢復的 pending workflow，也沒有「目前正在等哪一個欄位」及已驗證 slot revision。

目前只有部分 domain 有 typed draft。其他服務採下列不一致方式：

- 從 `lastUserText`／`lastAssistantText` 重解析；
- 只看 `lastAction`；
- 自行保留 domain draft，但沒有共用 pending-question fencing；
- 完全不承接，下一輪重新解讀原始需求。

所以使用者補一個答案後，系統常重新計算整份 missing list，或短答被路由成新意圖，造成重問、錯綁與
平行草稿互相污染。

### 2.4 現有測試與 skills 沒有阻止問題重現

- 既有 safety tests 主要檢查已知 marker；沒有證明 exception、provider error、image、focus-decorated、
  replay 與所有 channel 的最終字串。
- 現有 capability catalog 驗證 handler／能力目錄，但沒有要求每個 executable Intent 宣告 public reply、
  clarification、pending-state 與 mutation contract。
- Repo 與安裝版 conversation skill 的三份 reference 語意相同；安裝版 `SKILL.md` 仍指向舊
  `.codex/conversation-improvement-batches-2026-07-20.md` 路徑。
- 兩版 skill 都提到不要重問，但未把「每輪最多一個必要問題」、「typed pending slot」、「列舉全部出站點」
  與「actual LINE entry 才能關閉 LINE bug」寫成不可省略的 hard gate。
- Booking skill 只要求同時使用 conversation skill，因此會繼承相同缺口。

### 2.5 匿名化 runtime 證據

唯讀 aggregate 查詢未讀出或輸出私人 LINE 原文、external message ID、UUID 或完整回覆：

- 最近七天 54 則 LINE 文字出站中，同一 actor 連續收到完全相同回覆 2 次。
- `2026-08-02T11:35:17.5842273+08:00` 的現行 service generation 啟動後有 22 則文字出站，其中仍有
  1 則符合批量缺漏提示格式。
- UUID 形狀統計為 0；先前 diagnostic-marker 粗篩包含 sanitizer 自己的「系統內部」安全文案，不能把
  粗篩數字當成已證實的 raw leak。洩漏修復必須以 seeded failure-path 與使用者實際案例重現為準。

## 3. 不變量與範圍

### 3.1 使用者可見不變量

- 先回答當輪最重要事實，再提供下一步。
- needs-input 每輪只問一個可回答且真正阻擋執行的問題。
- 使用者已明確提供且驗證成功的資訊不得重問；只有修正、衝突、失效、過期或切換工作可重新確認。
- 使用者主動一次提供多個 slot 時全部接受並驗證，不要求拆成多句，也不再詢問已補齊欄位。
- 確認前不得宣稱完成；失敗、拒絕與 read-only 分支必須明說 mutation 是否為零。
- 所有成功、失敗、provider error、replay、圖片與 async 回覆都不得洩漏 internal diagnostic 或 identifier。

### 3.2 deterministic 不變量

- LLM 只輸出結構化 interpretation；required slot、問題順序、pending revision、focus／quote precedence、
  authorization、mutation 與 public diagnostic mapping 由 Java 決定。
- 明確 quote > active focus pending question > 唯一 pending workflow > bounded history > clarification。
- feedback、meta 與無 pending state 的單次 read-only 問題不得消耗 pending question。
- 同一 inbound replay 只能有一個 terminal reply、一個 pending revision 與精確 mutation count。
- pending state、domain draft 與 focus 必須 workspace／actor／channel-scope 隔離並通過 RLS。

### 3.3 套用出口

本計畫採全出口修復，不只 LINE：

- LINE text、image／OCR、error、role denial、idempotency replay；
- REST `/api/intent`；
- formatter、greeting、focus transition notice；
- provider failure 與未來 conversation async completion。

既有 REST `action`／`message` wire shape 維持相容；不得把新的 diagnostic 欄位加入 public JSON。

## 4. Phase A：建立唯一 Public Response Boundary

### 4.1 型別與資料流

新增 application-level public response contract，名稱可採下列固定設計：

- `PublicConversationReply`：使用者可見 message、`TerminalState`、optional `NextQuestion`。
- `ConversationDiagnostic`：僅供 trace/log 的 typed code，不可轉成 public message。
- `PublicConversationResponseService`：在所有 decorator 完成後、所有 adapter 出站前執行 final validation。
- `ConversationErrorResponsePolicy`：依 business／validation／provider typed code 映射固定 public fact 與單一
  next question。

`IntentResult` 可以保留既有 action、task、decision 與 focus metadata，但 public message 必須由上述 contract
產生；不得再用 arbitrary exception/model reason 當 public message。REST response schema 不變。

### 4.2 出站整合

- `IntentService` 不再公開 `BusinessException.getMessage()` 或 raw `command.reason()`。
- LINE `PreparedReply` 只能接收已 final-validated 的 `PublicConversationReply`。
- Image／OCR 的 `ReceiptResult` 需拆分 internal action 與 public reply，移除 DB file ID；若產品日後需要公開
  reference，另設計不可逆、可授權的 public reference，不重用 entity ID。
- Focus notice、occasion greeting、time preference 與 formatter 必須先組合，再做最後 public validation。
- Idempotency reservation 只保存已驗證的 public body；replay 出站仍重新通過 final boundary。
- LINE client 只負責 transport／format，不自行決定業務語句。
- 現有 marker scanner改成 case-insensitive defense-in-depth，覆蓋 UUID、package/class、SQL、schema、Intent、
  handler、validation、provider raw error、tokens 與內部識別形狀，但不得依賴 denylist證明安全。

### 4.3 Phase A gate

- 所有 LINE `messagingClient.reply(...)` 呼叫都只能取得 final-validated reply；直接 literal error 也由 public
  policy 產生。
- REST、LINE text、image、focus-decorated 與 replay seeded leak tests 全綠。
- Production source 不存在 exception/model reason 直接進 public result 的路徑。
- 不改任何產品 mutation 語意，相關 zero／exact mutation tests 維持綠。

## 5. Phase B：單題澄清與 typed pending state

### 5.1 Clarification contract

新增 `ClarificationStep`／`NextQuestion` typed contract：

- capability-owned stable question code；
- 一句自然問題；
- 對應 domain draft 的 slot；
- 適用條件與 deterministic 優先序；
- 是否阻擋 mutation／confirmation。

每次重新計算只挑第一個 unresolved step。`CLARIFICATION_NEEDED` 不再接收任意 missing-list 字串。Terminal
限制可以沒有問題；needs-input 必須有且只有一個 next question。

### 5.2 Durable pending question

使用實作開始時由 `origin/main` 驗證出的 next available Flyway version，新建 actor-owned
`conversation_pending_question`。不得預先假定 migration number，也不得與桌電 reserved schema lane 衝突。

最小 durable contract：

- workspace／actor ownership；
- channel 與 conversation-scope digest／key version；
- focus id、root domain、workflow id；
- stable question code；
- pending／answered／canceled／expired status；
- optimistic revision、expiry、created／updated time；
- inbound idempotency HMAC／fencing。

此表只保存「目前等哪一個問題」，不保存任意 raw LINE 原文或無型別 JSON slot bag。實際答案回寫各 domain
typed draft。啟用並強制 RLS；時間一律使用注入 `Clock`。

### 5.3 Domain 承接

- Calendar 與 School Transport 優先沿用既有 typed draft，補接 pending-question contract。
- Conditional recurrence、conditional venue、monthly ordinal、Task mutation 等需要跨輪補值的流程，使用
  既有可表達 draft；不足時建立該 capability 的 typed draft，不建立全域任意 command payload。
- 使用者回答目前 question 時，只更新對應 slot；同句另有明確 slot 一併驗證保存。
- 修正使舊確認失效時提升 draft revision並重算 next question。
- 平行 draft 有 quote 時只更新被引用者；無 quote 且不唯一時問一個選擇問題，零 mutation。
- feedback、read-only 插話後 pending 仍保留；切換工作遵循 Conversation Focus 原子 transition。
- 舊 `ConversationContext` 不刪除、不猜測遷移；首次遇到無 typed pending 的舊承接痕跡時，以一個自然問題
  安全重新建立 state。

### 5.4 Phase B gate

- 已確認的 bulk prompt services 全部改成一次一題。
- 每個多輪 flow 通過部分輸入、逐句補值、一次多值、亂序補值、修正、取消、過期、restart、quote、
  parallel draft 與 replay。
- 已回答 slot 在未失效前詢問次數為零。
- 所有 clarification／failure 分支 zero unintended mutation。
- RLS owner 可見；同 workspace peer、其他 workspace、system scope fail closed。

## 6. Phase C：全量對話模組 contract audit

### 6.1 Catalog coverage

不得硬編碼目前 enum 數量。測試以 `IntentCommand.Type.values()` 動態列舉，除 `UNKNOWN` 外，每個 executable
type 必須同時具備：

- domain handler；
- `conversation-capabilities.txt` entry；
- public-response contract；
- clarification strategy；
- pending-state strategy（`NONE` 或具體 contributor）；
- mutation class 與 confirmation requirement；
- focused success／failure regression。

另建特殊入口 catalog，覆蓋 `IntentService.doHandle(...)` 中不經 handler registry 的 deterministic
conversation services、image／OCR、focus control、failure explanation、feedback、help、LINE error／replay。

### 6.2 102 個 clarification 呼叫點處置

逐一分類並留下 machine-readable／test-verifiable 結果：

- terminal explanation：沒有 next question；
- single blocking question：使用 typed `ClarificationStep`；
- pending workflow：綁定 typed draft 與 durable pending question；
- invalid bulk／generic prompt：必須修正；
- non-public diagnostic：只留 trace，不得建立 public reply。

任何未分類呼叫點、未登錄特殊入口或新 Intent 缺 contract，catalog test 直接失敗。

## 7. Phase D：Skills 與文件防復發

### 7.1 Conversation skill

同步更新 repo 與安裝版 `develop-and-evaluate-conversation-capability`：

- `SKILL.md`：強制列舉全部 public outbound points；每輪最多一個必要問題；必須使用 typed pending state；
  LINE bug 必須有 actual-entry evidence，focused unit test 不足以關閉。
- `scenario-design.md`：加入逐題補值、已知 slot 不重問、一次多值、亂序、平行 drafts、feedback／read-only
  插話、image、decorator、failure、replay案例。
- `acceptance-rubric.md`：把 single-question、no-repeat、final outbound validation升為 hard gates。
- `generalization-checklist.md`：禁止以 last assistant text、exact phrase、prompt-only 或 denylist-only 修復。
- 修正安裝版舊 `.codex/...` batch path，改為 repo 現行 active/local path。

同步後以正規化換行的 semantic hash／line comparison確認 repo 與安裝版一致；安裝版在 workspace 外，執行時
必須走主機權限核准，不得只更新其中一份。

### 7.2 Booking skill

更新 `develop-and-validate-booking-execution`：provider availability／quote／authorization／payment／failure
公開回覆同樣必須通過 final public boundary、single-question 與 pending-slot gate；raw provider error、quote ID、
checkout/session ID不得公開。這不授權任何真實 booking 或 external mutation。

### 7.3 中央文件

- 更新本計畫 implementation ledger。
- 在 W11-H ledger記錄 conversation hardening baseline invalidation、新 runtime generation 與 release evidence。
- 更新 test strategy 的 focused／actual-entry／root 指令與測試數。
- 只有所有 gate 完成後才更新 architecture／development-plan 的完成狀態。

## 8. 測試矩陣與 hard gates

### 8.1 Repair 與永久 regression

- 由近期 owner-scoped LINE 問題建立匿名化 repair cases；不提交私人原文、精確地址、external message ID、
  UUID、API key 或可識別 payload。
- Public boundary seed：UUID、workspace／actor／node／plan／file ID、Intent enum、handler、schema、SQL、Java
  package/class、stack trace、exception、validation reason、router reason、prompt、token、provider raw error。
- 每個 executable Intent 至少覆蓋成功與 failure／missing-data public contract；有 mutation 者驗證 confirmation、
  exact mutation、rejection／failure zero mutation與 replay。
- 所有 needs-input reply以 typed metadata 驗證恰好一個 next question；純靠計算問號只作附加黑箱檢查，
  不取代型別 assertion。

### 8.2 Actual-entry scenarios

至少覆蓋下列自然對話家族：Calendar／Schedule、Task、Reminder、Place、Shopping／Item、Knowledge／Media、
Travel、Booking public preflight、Conversation Focus／quote、LINE image／OCR與 failure／replay。

每個多輪家族包含：

1. 初始只提供部分資訊，只問下一個必要問題。
2. 第二輪回答後不再重問，改問下一個未解問題。
3. 一句補多項時全部吸收。
4. 使用者修正已填 slot，舊確認失效且不改其他 slot。
5. feedback／meta／read-only 插話不消耗 pending。
6. 兩個平行 drafts 依 quote 更新；無 quote 時安全選擇。
7. duplicate LINE webhook 只有一次 mutation與一個 terminal reply。
8. failure／expired／unauthorized／cross-actor quote 零 mutation且零 leak。

### 8.3 Privacy、RLS 與 latency

- 所有 public channel success／failure／provider-error seed 零 internal leak。
- Pending state、domain drafts、focus、references通過 actor／workspace filter、NOBYPASSRLS、system scope與越權
  update=0。
- Low complexity terminal P95 ≤1.5 秒；medium P95 ≤4 秒且超過2秒有可靠 progress；high／long遵循 skill 的
  durable progress與 exactly-one terminal contract。
- 每個案例 UX 五維皆 ≥4/5；任一 hard gate 不得用平均分抵消。

### 8.4 執行順序

1. 先寫失敗 regression，證明已知 leak／bulk／repeat問題。
2. Phase A focused public-boundary tests。
3. Phase B pending／draft／RLS／replay tests。
4. Phase C catalog 與所有 dialogue-family neighbor tests。
5. LINE／REST／image actual-entry。
6. Spotless check、test-compile、affected full neighbor批次。
7. 透過 `scripts\mvn-safe.ps1 test` 執行全新 root regression；不得直接並行寫共用 `target`。
8. 修復後刷新 Java 21 runtime並執行官方 LINE E2E。
9. 桌電 sealed holdout／黑箱 public-response／latency evidence依 coordination protocol整合。
10. 建立全新的 24 小時且至少 20 筆真實 LINE text inbound baseline。

## 9. Monitoring closure

新 baseline 必須在最後一筆 production 修正、最後一次 runtime refresh 之後開始。出口至少包括：

- ≥24 小時；
- ≥20 筆 startedAt 後本人真實 LINE text inbound；
- diagnostic／identifier leak = 0；
- bulk missing-information prompt = 0；
- 已驗證 slot 重問 = 0；
- unexpected mutation = 0；
- replay duplicate mutation／duplicate terminal reply = 0；
- 新 trace、Calendar V2 route、privacy、scenario與 latency audit完成；
- synthetic probe、focused test、provider benchmark不得計入20筆真實 inbound。

若 monitoring 發現新問題，修正後必須再次刷新 runtime並重建 baseline；不得延續原窗口。

## 10. Separate-session 啟動與 ownership

新 session 必須：

1. 只在上述既有 W11 專用 worktree工作；不得另建worktree或只依branch重建環境。先驗證branch、HEAD、
   dirty state與 `origin/main` 是否前進，並保留所有已核准未提交修改。
2. 讀 root `AGENTS.md`、agent-context index、本計畫、W11-H、two-machine plan、trigger registry、architecture
   對話章節、test strategy與 conversation skill全套 references。
3. 把本計畫加入該 session 的 working plan；不得另起互相矛盾的 local plan。
4. 保持筆電為唯一 Intent／LINE／Conversation production writer；若偵測另一個 writer，fail closed。
5. 先重現至少一個 leak、一個 bulk prompt與一個 repeated-slot案例，再修改 production code。
6. 以 Phase A → B → C → D順序開發；不得先靠擴充 marker或prompt-only快速宣稱完成。
7. 每個 stable gate更新本文件 ledger；未通過測試或混合 ownership內容不得標 PASS／commit。

2026-08-06 continuation snapshot：固定worktree為
`D:\my-project\my-mobile-secretary\var\worktrees\calendar-w11`，branch為
`codex/calendar-w11-cutover`，signed HEAD為`b32cb0d05db5feb78a5671380d3fc0836dd2b970`。唯讀
`git status --short --untracked-files=all`共253項：staged 0、unstaged 121、untracked 132；全部視為既有
核准conversation hardening ownership，不得reset、stash、clean、restore、覆蓋或移到新worktree。第十五次
baseline startedAt為`2026-08-06 00:04:55.740380 +08:00`，notBefore為
`2026-08-07 00:04:55.740380 +08:00`，起始LINE text inbound 103、closure門檻123。新session先確認
runtime generation `06747128162542bcb086fd19e09c917f`與官方LINE健康仍有效，再監測本人真實turn；不得
把synthetic probe／tests／provider benchmark計入，也不得在closure與portable tracked evidence完成前commit、
push、PR、merge或宣告READY。

建議 context 壓縮候選點：Phase A public boundary全綠、Phase B migration／RLS／multi-turn全綠、Phase C catalog
100% coverage、root regression全綠、runtime refresh／monitoring開始。候選點不是強制壓縮；壓縮前必須落盤
決策、修改檔案、測試、未完成項與下一步。

### 10.1 Secretary tone review gate

全服務 public reply 的口吻、feedback 變體、自稱／使用者稱呼與承諾真實性，統一由
[`conversation-public-reply-tone-inventory.md`](conversation-public-reply-tone-inventory.md) 管理。該文件是
本計畫的 supporting inventory，不是另一份 execution plan。使用者確認 inventory 前，不得開始下一輪
production implementation；任何「記住」「已處理」「重新處理」「會通知」「會接著處理」句型都必須
先取得文件所列 typed evidence，不能只靠文案或模型推測。

## 11. Implementation ledger

| Gate | Status | Evidence |
| --- | --- | --- |
| Investigation／plan | PASS | Code-path audit、clarification inventory、skills semantic comparison、匿名化 runtime aggregate |
| Phase A public boundary | PASS_VERIFIED | 三個 seeded regressions 先以 38 tests／3 failure 重現；修復後 focused 90／90、formatter／focus／replay／OCR／failure／catalog neighbour 138／138 PASS。UNKNOWN／compound／ungrounded 的固定 public-safe mapping 已納入 108／108 neighbor、232／232 expanded 與 fresh root gate；官方 LINE E2E connected |
| Phase B single-question／pending state | PASS_VERIFIED | V98 已建立 actor/scope/workflow fenced pending question 與 `schedule_clarification_draft` capability-specific typed columns；School Transport 新寫入亦為 typed columns 且 `payload IS NULL`。Conditional Recurrence／Venue／Monthly 支援 restart-style follow-up、validated-slot no-repeat、active-pointer fencing、明確新 workflow 原子切換與可信 LINE quote 優先。多草稿無 pointer 的單題選擇、deterministic ordering/lock、精確 resume、selection-only cancel、active draft cancel、correction/expiry、generic new-action bypass 與 decision-period typed answer 均完成；actual-entry／RLS 10／10、expanded gate 232／232、fresh root PASS |
| Phase C all-module catalog audit | PASS_VERIFIED | `IntentCommand.Type.values()` handler contract 與 capability catalog 動態覆蓋；目前 55 個 production source／158 個 clarification occurrence 均有 public／clarification／pending／mutation／regression 分類，inventory 與 catalog tests 皆通過；sealed holdout 21／21、fresh root 1,963 tests PASS |
| Phase D skills／docs sync | PASS_VERIFIED | repo 與 installed conversation skill 的 SKILL／3 references，以及 repo 與 installed Booking skill 的 SKILL／2 references 均已同步；normalized-newline semantic comparison 7／7 equal。Booking 僅更新 provider/public-reply final boundary 與 single-question 規則，沒有交易 authority。隔離 TEMP venv 的官方 `quick_validate.py` 以 UTF-8 模式驗證 repo conversation、installed conversation、repo Booking、installed Booking 四份皆 VALID。architecture、test strategy、capability catalog 與 W11 ledger 已同步 hard gates |
| Secretary tone／truthful commitment inventory | PASS_VERIFIED_LOCAL | supporting inventory、typed evidence carrier、truthful commitment gate、final tone policy、10+10 feedback variants、voice profile與typed draft均完成；neighbor／sealed／actual-entry／RLS／replay／LINE API、repo／installed skills semantic comparison及官方validator均通過。最新fresh root與官方LINE E2E亦通過；release仍另受provider、獨立desktop evidence與新baseline gate約束。 |
| Focused／RLS／actual-entry | PASS_VERIFIED | 最後 production 版本的 neighbor/catalog/public-safety/sealed 101／101、V100/V101/RLS/actual-entry/LINE/API/latency 53／53 PASS；中文數字 timed planning、structured event intake、缺地址 custom-place guidance 與 root failure 精準回歸 17／17 PASS。20-sample system-place actual-entry P95 75 ms、max 81 ms、model/token usage 0、business mutation 0；secretary sample P95 187 ms、max 199 ms。 |
| Root regression | PASS_VERIFIED | Context-transition final production版的非clean root與fresh `scripts\mvn-safe.ps1 -Clean test`均為2,128 tests、0 failure／error、18 skipped；clean耗時702.0秒。 |
| LINE E2E／new monitoring baseline | INVALIDATED_BY_CONTEXT_CHOICE_SHORT_ANSWER_AND_LEGACY_IDENTITY_FAILURE | 第十七次窗口已永久失效；不得沿用startedAt `2026-08-09 19:30:02.221128 +08:00`、起始110、closure 130、generation `4da1b6e5eddf466596baafee6aaa58bc`或其receipts。 |
| W11 release | BLOCKED_PRODUCTION_REPAIR | 真人LINE證明context choice自然短答落入`UNKNOWN/FAILED`，且舊route pending的typed workflow雖正確，root domain／safe label仍沿用錯誤舊metadata。須修復並重跑完整release gate與新baseline。 |

2026-08-04 route-origin/context hardening increment：第十三次 baseline 已永久標記
`INVALIDATED_BY_ROUTE_ORIGIN_CONTEXT_HARDENING`，不得沿用 startedAt `2026-08-04 17:36:50.492339 +08:00`、
98 筆起始 counts或runtime generation `8836acc791b14607b933333a0ecabc99`。Phase 1 failure-first 已重現 raw
`endAt` 錯分流、14 小時缺地點誤 adjacency與精確點位 disclaimer；Phase 2 改為 persisted typed journey
semantics、6 小時 unlinked bound及 exact-point public boundary。V109 建立 actor-owned HOME preference與
owned Place FK、ENABLE/FORCE RLS；V110 保存 route journey kind。缺 origin 以 typed Calendar draft承接，
continuation 在首次 mutation 前跨 replay boundary。最新 focused unit 46／46、home/migration/RLS/actual-entry
integration 14／14與 typed journey targeted 3／3 PASS；skill repo/installed 四檔 official validator valid、
normalized-newline 4／4 MATCH。expanded neighbor／catalog／clarification inventory／REST／LINE／sealed／fresh
clean root／runtime／官方 LINE E2E 尚未完成，不能把本 increment 標 PASS 或 READY。

2026-08-04 latest secretary-tone increment：使用者確認一般稱讚不得推論或保存長期回答偏好；只有明確
future-facing 指令可提交系統真正支援的 typed style。failure-first 以
`以後都用這種方式回答` 重現 empty route（1 test／1 error）；V106 增加 nullable bounded
`CONCISE_WARM_SECRETARY` profile value。修復後 voice/style unit 13／13、actual-entry＋FORCE RLS 10／10 PASS；
一般稱讚實測 `response_style IS NULL`，明確指令可 commit/read-back，原 business pending 保持 PENDING，
task mutation=0。V105 restaurant guidance draft 逐輪只問 restaurant／dining-at／party-size 一題，完成時明說
未向餐廳送出訂位或付款；focused 51／51、actual-entry/RLS batch 13／13 PASS，沒有 provider、payment、
cancellation 或 browser mutation。最新 fetch 驗證 origin/main 仍為 `132308e42d8a4c5dcd4929372a885fe1db66fd8a`，
為 current HEAD ancestor，upstream latest migration V93，V105／V106 無 collision。所有既有 root/runtime/
monitoring receipt 因後續 production 修正失效，仍須 fresh full gates、runtime、官方 LINE E2E 與新 baseline。

2026-08-04 latest full verification：catalog／clarification inventory／UX／sealed batch 41 tests（1 個明確
opt-in skipped）PASS；Intent API／LINE webhook／natural-language replay 115／115 PASS；expanded actual-entry／
V98–V106 migration／FORCE RLS／quote／restart／replay／privacy／latency 117／117 PASS。20-sample secretary
actual-entry P95 131 ms、max 144 ms，system-place P95 78 ms、max 91 ms，兩者 business mutation=0。第一次
fresh `-Clean test` 2,049 tests 找到 5 個 stale test data/oracle（當日 09:00 已過、舊 emoji 與舊「你」口吻）；
production code 未變，精準鄰接 12／12 PASS。第二次 fresh `-Clean test` 2,049 tests／0 failure／0 error／
21 opt-in skipped PASS，耗時 508.6 秒。runtime refresh／官方 LINE E2E／新 24h baseline 尚未完成。

2026-08-04 runtime/new baseline receipt：第一次不帶 `-SkipDocker` 的正式啟動因重開機後既有同名
`mms-postgres`／`mms-redis` 為 Exited 而 fail closed；沒有刪除、recreate、rename 或清資料。直接啟動這兩個
既有容器且確認 healthy 後，`dev-start.ps1 -SkipDocker` exit 0；`dev-status.ps1 -ExternalLineProbe
-VerboseOutput` exit 0，Spring Boot UP、PostgreSQL／Redis healthy、ngrok running、LINE→ngrok→Spring Boot
connected，Dispatcher DISARMED/failure-isolated。runtime generation `4198a8d30ce243a190d3845a0ae0fe6b`，
Flyway V106。新 baseline 以 `REPEATABLE READ READ ONLY` transaction 於
`2026-08-04 09:53:46.582781 +08:00` 建立，notBefore `2026-08-05 09:53:46.582781 +08:00`；起始
LINE inbound／text／outbound／decision trace 均為 91，trace outcome 為 SUCCEEDED 46、CLARIFICATION 42、
FALLBACK 3、FAILED 0，task 1、legacy schedule 4、Calendar plan 1、intent issue 60、pending question 1、
pending repair 0、pending restaurant guidance 0、Flyway V106。snapshot 不讀原文、external message ID、UUID、
payload 或 secret；closure 至少需各 111，新增 20 筆必須是本人真實 LINE text inbound，synthetic probe、
focused tests 與 provider benchmark 不得計入。W11 在 24 小時與 closure privacy/mutation/latency audit 前非 READY。

2026-08-03 route-itinerary repair：新增 `PLAN_ROUTE_ITINERARY` typed capability，system-place 僅提供 entity
evidence，不再攔截起訖行程操作；高信心 route composition 在一般日期／行程查詢捷徑前執行，只有模型
輸出單一同型 command 才可 mutation，否則單題回問且零 mutation。活動驗證成功後先建立一個活動，再以
typed Calendar draft 詢問是否規劃交通；接受後保存 LOCKED／WINDOWED／FLEXIBLE 與 provider-neutral mode，
可靠 route evidence 只產生一個 internal departure node。V102 僅增加 typed columns，route pending workflow
綁定同 actor／workspace／channel／scope draft，transport node 有 owned composite FK。failure-first 已重現
system-place bypass、daily schedule fake success 與 UNKNOWN；修正後 focused 38／38、migration／RLS／provider／
actual-entry 17／17、neighbor／catalog／RLS／REST／LINE 145／145 PASS。第一輪 fresh root 2,003 項只由
clarification inventory 擋下新增 call-site 分類；inventory 與對應 route regression 17／17 PASS 後，第二輪
fresh root 2,003／2,003、21 opt-in skipped PASS，耗時 576.6 秒。runtime、官方 LINE E2E 與新 baseline
尚未完成，release 維持 BLOCKED。

為避免沿用 system-place latency 代替新能力，另新增 route actual-entry 20-sample hard gate：每筆精確
一個 top-level plan、零可見子活動、零 `transport-departure`，公開回覆不含 internal type／actor／workspace，
P95 ≤ 1,000 ms；完整 test class 7／7 PASS。此 test-only gate 納入後的最終 fresh root 2,004／2,004、
21 opt-in skipped PASS，耗時 603.9 秒。首次 runtime baseline 因測試期間正式停機而不沿用，須在重新
refresh 與 LINE E2E 後另建起點。

最終 runtime 已於 2026-08-03 21:18:38 +08:00 以健康 shared PostgreSQL／Redis 刷新，未重建資料容器，
Dispatcher 維持 DISARMED；官方 LINE→ngrok→Spring Boot probe connected。第九次 baseline 於
2026-08-03 21:20:53 +08:00 以 `REPEATABLE READ READ ONLY` aggregate transaction 建立：LINE inbound／
text／outbound 皆 89、decision trace 89（SUCCEEDED 44、CLARIFICATION 42、FALLBACK 3、FAILED 0）、
Flyway V102。快照未讀私人原文、payload、external ID 或 UUID；notBefore 為 2026-08-04 21:20:53 +08:00，
且至少需 20 筆新的本人真實 LINE text inbound（closure count ≥109）。在時間與流量皆滿足前不得標 READY。

2026-08-03 使用者再次確認產品必須持有自己的公共地點資料，而不是只依賴 Google。已新增 immutable
`system-place-catalog.tsv`，解析順序固定為 user custom place → system catalog → external provider → 一個
typed disambiguation question。snapshot 共 531 筆：捷運 202、台鐵客運站 244、高鐵 12、機場 17、商港 7、
縣市政府 22、中大型遊樂園 27；來源標示 TDX、交通部民航局／港務公開資料、地方政府與觀光署，snapshot
日期為 2026-08-03。catalog 不保存 LINE 原文或使用者資料，也沒有 provider／booking mutation authority。
先以 `PlaceServiceTest#publicInfrastructureLookupUsesSystemOwnedCatalogWithoutGoogle` 重現 1／1 failure，
修復後 system catalog／service／handler focused 19／19 PASS；Docker 重開後再以實際 LINE webhook entry
驗證 1／1 PASS，並確認 custom place count 不變、internal category enum 未公開。此為第六次 baseline
invalidated 後的新 production 修改，仍須重新完成 neighbor／catalog／RLS、fresh root、runtime refresh、
官方 LINE E2E 與全新 24h／20 inbound baseline，W11 仍為 BLOCKED。

第六次 baseline 起點後、此次 fresh runtime 前新增的 17 筆本人 LINE text 已以 aggregate-only 方式納入
repair evidence：17 inbound／17 outbound，9 SUCCEEDED、8 CLARIFICATION，issue 為 8 clarification／1 feedback；
diagnostic/identifier leak、multiple-question candidate、bulk-missing candidate 均為 0。公共地點類需求實際
包含捷運、高鐵與台鐵／車站類，未輸出或保存原文。system catalog snapshot 重新驗證 531 筆、每列 8 欄，
分類精確為 202／244／12／17／7／22／27。`SecretaryTurnActualEntryLatencyTest` 的 20 個 warm sample
測得 P95 146 ms、max 146 ms、business mutation 0；threshold 仍為 1,500 ms，未降低門檻。

第七次 baseline開始後、尚無新 inbound時補取 mutation/pending 起始值：task 1、legacy schedule 4、
Calendar V2 plan 1、pending question 1、pending repair 0。後續 closure 以這組起始值核對 exact intended
mutation、zero unintended mutation與 feedback/meta/read-only不消耗既有 pending；不讀取 pending內容或使用者原文。

Baseline 執行期間的 completion audit 另補上匿名化 20-case UX 五維 scorecard，涵蓋 19 類情境與
LINE text、image/OCR、error、role denial、replay、REST、formatter、greeting、focus notice、notification、
provider reply 等 outbound family；每案例每維最低 4/5，所有低於 5 的分數均有理由，且引用的 regression
method 無缺漏。因 active runtime 的 `spring-boot:run` 正確持有共用 Maven target lease，正式 Maven runner
未繞過鎖；改以唯一 `%TEMP%` class output 做不寫共用 target 的補充編譯／reflection execution，3 checks PASS。
此結果不是正式 Maven/root gate，須在 baseline closure 後由 `mvn-safe.ps1` 重跑新增 tests 與 fresh root。

2026-08-03 最新 14 個本人真實 LINE text turn 的 aggregate-only repair evidence：14 inbound／14 outbound，
10 個 UNKNOWN clarification、1 個 ASK_PLACE clarification、1 個 ASK_PLACE success、1 個 feedback與1個
deterministic success；至少7筆帶公共地點類別訊號。UNKNOWN 固定回覆群組各重複最多4次，第四輪重新
回到第一題；feedback回覆沒有指出公共地點落差，也沒有同輪repair。13個model turn為7.1–17.1秒，
model stage P95約13.4秒，確認瓶頸不是parsing。兩個新增 regression 先以17 tests／1 failure／1 error重現
第四輪循環與Calendar V2未接system catalog；未輸出LINE原文、external id、UUID或私人payload。

本輪 system-place conversation repair 已以 Java typed resolution 區分唯一命中、同一大型地點多點與跨縣市
實體歧義；custom place 仍優先。唯一命中可直接進 Calendar typed snapshot；大型地點多點不提早追問，
最終 materialization 才選點並公開理由；跨縣市歧義只問一個縣市問題。V100/V101 新增 actor／workspace／
channel／scope fenced `public_place_lookup_draft` 與 Calendar draft reference，僅保存 stable catalog keys、
workflow/question/revision/fencing，啟用並強制 RLS，不保存 raw LINE text 或 generic JSON slot bag。縣市回答
完成 public-place draft 與 Calendar materialization 在同一 transaction；feedback/meta/read-only 不消耗 pending。
缺少座標的 catalog point 明確回報 route evidence 不足且 estimator call=0。UNKNOWN durable question code 逐輪
縮小範圍，完成有限序列後結束，不再循環第一題；deterministic system-place/meta/category shortcut 不進模型。

目前 current evidence 為：最初 17 tests／1 failure／1 error 的 failure regression；修復後 28／28、31／31、
actual-entry/RLS 9／9、neighbor/RLS/latency 62／62、correction/custom precedence 13／13，以及最後缺座標
相依 focused 18／18 PASS。20 個 warm system-place actual-entry sample 為 P95 120 ms、max 136 ms、model/token
usage 0、business mutation 0；既有 agenda sample P95 178 ms、max 199 ms。repo／installed conversation skill
五個檔案 normalized-newline semantic comparison 5／5 equal，repo conversation、installed conversation 與
Booking 三份 `quick_validate.py` 均 `Skill is valid!`。能力目錄 ASK_PLACE entry 已同步新契約。這些證據仍不
替代最後 production 變更後的完整 catalog／RLS／actual-entry／sealed、fresh root、runtime／LINE E2E 與
全新 24h／20 inbound baseline。

最後 production 版本已完成 neighbor/catalog/public-safety/sealed 101／101、V100/V101/RLS/actual-entry/
LINE/API/latency 53／53 PASS。第一輪 fresh root 1,988 tests 找到 structured event intake 與缺地址 custom
place guidance 兩個跨模組回歸；修正後精準 17／17、前置 gates 全數重跑，第二輪 fresh root 1,990 tests／
0 failure／0 error／21 opt-in skipped PASS，耗時 614.5 秒。Java 21 runtime generation
`3ea45cdb209147fcac76c31207d71b66` 已刷新，官方 LINE→ngrok→Spring Boot E2E connected；主服務 UP、
PostgreSQL/Redis healthy，Dispatcher 維持 DISARMED。

第八次 monitoring baseline 已於 `2026-08-03T16:40:36.535997+08:00` 以 PostgreSQL `REPEATABLE READ
READ ONLY` transaction 建立，notBefore 為 `2026-08-04T16:40:36.535997+08:00`。起始 LINE inbound／
text／outbound 均為 85；decision trace 85，outcome 為 SUCCEEDED 41、CLARIFICATION 41、FALLBACK 3、
FAILED 0；task 1、legacy schedule 4、Calendar plan 1、intent issue 59、pending question 1、pending repair 0、
pending public place 0、Flyway V101。快照只含 aggregate metadata，未讀出私人原文、payload、external
message ID、UUID 或 secret。closure 至少需 inbound/text 各 105，新增 20 筆必須是 baseline 開始後本人
真實 LINE text turn；synthetic probe、focused tests 與 provider benchmark不得計入。

2026-08-02 最新協作／環境 preflight：重新 fetch 後 `origin/main=132308e42d8a4c5dcd4929372a885fe1db66fd8a`，
它仍是 signed HEAD `b32cb0d05db5feb78a5671380d3fc0836dd2b970` 與目前 HEAD 的 ancestor，且
`merge-base(HEAD, origin/main)` 精確等於 `origin/main`；未發現 V98 migration collision，因此 ownership
contract 仍有效。Testcontainers gate 的外部阻塞為 Docker Desktop 長時間 `starting`、API 500、
`docker-desktop` distribution 未掛載 bundled ISO（`/opt/docker-desktop` 不存在）且沒有
`dockerd`／`containerd`。已嘗試官方 start/restart、僅 terminate `docker-desktop` distribution 後重啟、
背景與可見 Desktop 啟動，仍未恢復；未執行會影響 Ubuntu／其他 lane 的全域 `wsl --shutdown`，亦未
重設 Docker data。新增 actual-entry／RLS、fresh root、runtime／LINE E2E 必須等 engine 恢復後重跑，
現有 unit／sealed gate 不得替代。

第五次 monitoring baseline 已於 2026-08-02 本輪第一筆 conversation hardening production code 修改時標為
`INVALIDATED_BY_CONVERSATION_HARDENING`。舊 runtime generation 與其 24h／20 inbound window 不得再用於
release；只能在最後一筆 production 修正、runtime refresh 與官方 LINE E2E 成功後建立全新 baseline。

### 2026-08-04 system-place knowledge／point-query repair ledger

- 匿名化最新 LINE evidence 顯示：knowledge turn 被回成 95 字 itinerary-deferred hub 說明，下一輪短點位
  問題則遺失候選並產生無關 location clarification。未輸出或提交私人原文與識別碼。
- Failure-first 20 tests／2 expected failures；修復以 typed `KNOWLEDGE/LOCATION/LIST_POINTS/FILTER_POINTS`
  operation、data-driven logical place、V107 `READ_ONLY_MULTIPOINT` actor/scope browse 實作。browse 只存
  catalog keys/revision/fencing，不建立 pending question、不保存 raw text/JSON、不建立 custom place。
- Focused 30／30；最後 production 版本的 catalog／RLS／Calendar actual-entry／route neighbor／actual LINE／
  sealed／latency gate 89／89 PASS。20-sample knowledge P95 64 ms、max 73 ms，model/token 0、business
  mutation 0。repo／installed conversation skill normalized-newline 4／4 equal，skill-creator validator 2／2 valid。
- 第一輪 fresh root 2,061 tests／1 inventory failure；補登第二個 reviewed clarification call site後精準
  24／24 PASS，第二輪 fresh root 2,061／2,061、21 opt-in skipped、0 failure／error，耗時 608.8 秒。
  Runtime generation `234a8bd6b14e401083797b80394a0208` 已刷新，Flyway V107，main UP，PostgreSQL／
  Redis healthy，Dispatcher DISARMED/failure-isolated，官方 LINE→ngrok→Spring Boot connected。
- 第十二次 baseline 於 `2026-08-04 12:33:52.555708 +08:00` 建立：LINE text inbound／outbound與
  decision trace 均為 94；task 1、schedule 4、Calendar plan 1、custom place 1、pending question 1、
  pending multipoint browse 0。closure 至少各 114 且 elapsed ≥24h；synthetic probe／tests 不得計入。
  在 closure 前本 ledger 仍不宣告 stable／READY。
- 2026-08-04 最新本人 LINE evidence 重現 route-itinerary 語意分裂：相同完整起訖／時間句型只把
  「規劃」改為「安排」便分別落到 deterministic schedule list 與 `PLAN_ROUTE_ITINERARY`；後者首輪
  provider invocation為0、只留下 timed-point pending draft。第一批 failure-first regression 17 tests中
  14 PASS、2 failure／1 error，三個紅燈精確為 synonym routing、public point-label round-trip與首輪
  provider-first。此輪第一筆 production 修正前已將第十二次 baseline 標為
  `INVALIDATED_BY_CONVERSATION_HARDENING`；舊 runtime generation及窗口不得用於release。
- route repair 已完成同義句 deterministic routing、public point label round-trip、standalone provider-first、
  V108 typed unavailable/retained/available state、typed retry、direct overlap與前後銜接風險、route preview、
  active-focus fencing、TDX walking-only與Google attribution。TDX read-only實測在walking-only參數下3／3
  成功、transit 3／3、付費叫車段0／3，P50 833 ms、P95 990 ms；本機Google Routes key尚未配置，故
  provider預設仍為TDX_PRIMARY，未以推測改成Google。重開機後current-version staged regression為
  169／169；conversation/Booking skill normalized semantic 7／7 equal、官方validator 4／4 valid；全新
  clean root regression 2,074 tests／0 failure／0 error／21 opt-in skipped PASS，耗時654.0秒；後續runtime／
  官方LINE E2E與新baseline結果如下。
- runtime generation `8836acc791b14607b933333a0ecabc99` 已刷新，main UP、PostgreSQL／Redis healthy、
  Dispatcher DISARMED/failure-isolated；官方LINE E2E connected，DEV_RUNTIME／LINE_E2E fresh receipt皆MATCH。
  第十三次 aggregate-only baseline於 `2026-08-04 17:36:50.492339 +08:00` 建立，notBefore
  `2026-08-05 17:36:50.492339 +08:00`；起始LINE inbound／text／outbound／trace皆98，task 1、legacy
  schedule 4、Calendar plan 1、custom place 1、pending question 1、pending public-place browse 1、Flyway V108。
  closure需text inbound至少118且滿24小時；未讀或保存私人原文、external ID、UUID、地址或payload。
- 第十三次 baseline 在 route-origin/context production hardening 第一筆程式修改前標為
  `INVALIDATED_BY_ROUTE_ORIGIN_CONTEXT_HARDENING`；原startedAt、notBefore、counts與runtime generation
  不得再作release evidence。只有最後production修正、fresh root、runtime refresh及官方LINE E2E全綠後
  才能建立新24小時／至少20筆本人真實LINE text inbound窗口。
- route-origin／context hardening 最終 gate：expanded focused／neighbor／catalog／inventory／migration／
  RLS／actual-entry 74／74 PASS；REST／LINE webhook／replay／sealed／privacy 123／123 PASS；fresh
  `scripts\mvn-safe.ps1 -Clean test` 2,091 tests、0 failure／error、21 opt-in skipped PASS，耗時 692.5 秒。
  runtime generation `8aee51c77c0b4bc38ffe1a78210d800a` 已刷新，main UP、PostgreSQL／Redis healthy、
  Dispatcher DISARMED/failure-isolated；官方 LINE→ngrok→Spring Boot connected，DEV_RUNTIME／LINE_E2E
  fresh receipt 均 MATCH。repo／installed conversation skill normalized-newline 4／4 equal，skill-creator
  官方 validator 2／2 valid。
- 第十四次 aggregate-only baseline 已於 `2026-08-04 20:57:17.151654 +08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction 建立，notBefore 為
  `2026-08-05 20:57:17.151654 +08:00`。起始 LINE inbound／text／outbound／decision trace 皆 100；trace
  outcome 為 SUCCEEDED 54、CLARIFICATION 43、FALLBACK 3、FAILED 0；task 1、legacy schedule 4、Calendar
  plan 1、intent issue 61、custom place 1、pending question 1、pending public-place browse 1、active HOME
  preference 0、pending Calendar draft 3、Flyway V110。快照未讀或保存私人原文、external ID、UUID、
  地址、payload 或 secret；closure 必須 elapsed ≥24h 且 LINE text inbound ≥120，其中新增 20 筆均為
  startedAt 後本人真實 turn，synthetic probe／tests／provider benchmark 不得計入。closure privacy／
  mutation／latency audit 完成前，本 lane 仍非 READY。
- 第十四次 baseline 已在 route reply／buffer／Google Maps production hardening 第一筆程式修改前永久標為
  `INVALIDATED_BY_ROUTE_REPLY_BUFFER_MAP_HARDENING`。原 startedAt、notBefore、100 筆起始 counts、120 筆
  closure threshold、runtime generation `8aee51c77c0b4bc38ffe1a78210d800a` 與其 runtime／LINE receipt 均不得
  再作 release evidence。只有最後 production 修正、fresh clean root、runtime refresh 與官方 LINE E2E
  全綠後，才能建立新的 24 小時／至少 20 筆本人真實 LINE text inbound baseline。
- 2026-08-05 route reply／buffer／Maps staged implementation：加入 typed route operation preference、V111
  FORCE RLS、V112 typed endpoint source、standalone 掃讀卡片、所有相關衝突行程名稱、開車停車／計程車
  等車與 replay-safe 叫車提醒，以及可信 endpoint 才能輸出的 Maps link。repo／installed conversation skill
  normalized-newline 4／4 equal，skill-creator validator 2／2 valid；focused 39／39、neighbor／catalog／
  inventory／REST／LINE webhook／replay／sealed 160／160 PASS；另以 failure-first actual-entry 擋下 `(0,0)`
  Maps 連結後重跑通過。fresh clean root、runtime、官方 LINE E2E與新 baseline仍待最後 production version，
  因此非 READY。
- 最終 local execution receipt：fresh `scripts\mvn-safe.ps1 -Clean test` 2,100 tests、0 failure／error、
  21 opt-in skipped PASS，耗時 607.0 秒。runtime generation
  `bf8f440f5cea41f2b6a61a9c8e5d3061` 已刷新，main UP、PostgreSQL／Redis healthy、Dispatcher
  DISARMED/failure-isolated，官方 LINE→ngrok→Spring Boot connected；fresh DEV_RUNTIME／LINE_E2E 均
  MATCH。W11 Automatic review工具拒絕把 evidence寫入 calendar-w11 worktree並只允許 primary root；依
  root dirty ownership限制未繞過，已登記 tooling backlog。零-open-blocker review與新24h baseline未完成，
  因此仍非 READY。
- 上述 local execution、runtime 與 LINE E2E receipt 已因 safe-time typed reschedule production 修正永久標為
  `INVALIDATED_BY_SAFE_TIME_ROUTE_RESCHEDULE`。2,100 tests 結果、runtime generation
  `bf8f440f5cea41f2b6a61a9c8e5d3061` 及其 runtime／LINE receipt 均不得再作 release evidence；必須待本次
  failure-first、provider revalidation、exactly-once mutation 與 replay gate 完成後重新取得 fresh evidence。
- safe-time typed reschedule staged evidence（2026-08-05）：failure-first 先重現選擇「往後安排到安全時間」
  回 `AI_UNAVAILABLE`；production 改為從 persisted route interval與6小時 bounded adjacency算缺口，provider
  revalidation成功且 hypothetical assessment安全後，原 plan/draft/start/end exactly-once 更新並 resolve risk。
  provider unavailable replay曾重現重複 provider invocation，移動 replay reservation至重新查詢之前後修復。
  standalone actual-entry 11／11、route neighbor 42／42、catalog／inventory／REST／LINE webhook／sealed 99 tests
  （1 opt-in skipped）PASS；fresh clean root、runtime、官方 LINE E2E與 Automatic review仍待最後 gate。
- safe-time local final evidence：fresh `scripts\mvn-safe.ps1 -Clean test` 2,103 tests、0 failure／error、
  21 opt-in skipped PASS，耗時 653.7 秒；runtime generation `6a1653e98d1f480698712d327750cde3` 已刷新，
  main UP、PostgreSQL／Redis healthy、官方 LINE E2E connected，Dispatcher未啟用。重新 fetch後
  `origin/main` 仍為 `4fd39eb13f1a181d6522d4dac00ac3ff88b64fe1`，尚無 worktree-compatible Automatic
  review修復；依 ownership／trigger registry 未繞至 primary root寫 evidence，因此仍非 READY且不得開 baseline。
- safe-time completion audit補齊 direct-overlap continuation、前後雙側風險與 stale node revision atomic
  rollback；正式 Calendar revision API 建立 stale 狀態，DB editor guard仍維持有效。擴充後 actual-entry
  11／11 PASS；current-tree fresh `scripts\mvn-safe.ps1 -Clean test` 2,105 tests、0 failure／error、21 opt-in
  skipped PASS，耗時 594.5 秒。這兩項是 test-only hardening，production binary未變；為釋放 Maven lease，
  runtime已由受管 `dev-stop` 停止且保留資料庫，baseline前仍須重新取得 fresh runtime／官方 LINE E2E。
- Automatic review producer已定位為 PR #23（`tooling/docker-shared-lifecycle-review-20260805`，head
  `09c75ae4aad3a2fc252dfc4cbe01224cd7f31c63`），包含 registered-worktree／approved evidence root驗證，
  但截至 2026-08-05 21:30:06 +08:00 仍為 Draft、GitHub `CONFLICTING`／`DIRTY`、0 checks且未進
  `origin/main`。calendar lane依 path ownership不得替 tooling lane解 conflict或 merge；consumer只能等待
  producer把修復與驗證合併後重新 fetch，不能使用 PR branch或 primary-root evidence提前開閘。
- PR #23 後續 head 已更新為 `c1163090f8b3d285dbb6e92442fe75ebe18340f8` 並成為 GitHub
  `MERGEABLE`，Fast tests、Integration shards 0／1／2與 Automated regression complete均 PASS；唯一失敗為
  merge policy不允許 tooling branch修改 `docs/agent-context/development-environment-preflight.md`。PR仍為
  Draft且未合併；producer須擴充 tooling allowlist（並涵蓋其正式 environment evidence root）與 regression，
  重新跑綠後合併。calendar consumer在此之前無合法替代路徑。
- PR #23 已合併至 `origin/main@3b01ba55fbb33467f1449190e840e06e01cc4990`，worktree target evidence
  ownership正向路徑已實跑成立，fresh SOURCE_WRITE／MAVEN均 MATCH。最終 review仍因 stopped shared container
  port validator只讀空的 `NetworkSettings.Ports` 而 BLOCKED；durable `HostConfig.PortBindings` 與其他
  coordination identity均正確，依契約沒有手動啟動或替換 container。review JSON另包含不可發布且跨機不可攜的
  absolute paths，兩項已登記 tooling backlog。conversation／Booking repo與installed完整 skill tree 10／10
  normalized-newline相等，skill-creator官方 validator 4／4 valid；Booking未取得訂位、付款、取消或任何
  provider mutation authority。current surefire reports重算2,105 tests、0 failure／error、21 skipped；此後
  production binary未變。tooling修復、零-open-blocker Automatic review、fresh runtime／官方 LINE E2E與新
  24h／20 inbound baseline仍未完成，因此維持非 READY。
- Docker shared containers 已由使用者啟動並驗證 `mms-postgres`／`mms-redis` healthy；calendar worktree
  `dev-start -SkipDocker -SkipDispatcher` exit 0，runtime generation
  `06747128162542bcb086fd19e09c917f`，Dispatcher維持停用。官方 `dev-status -ExternalLineProbe` 回報
  main UP、PostgreSQL／Redis healthy、LINE connected。以 `origin/main@3b01ba55fbb33467f1449190e840e06e01cc4990`
  正式 tooling runner重跑 W11 Automatic review，READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／
  LINE_E2E／EXTERNAL_PROVIDER 7項全數 MATCH、open blocker 0、contract fingerprint
  `e43874472f69f3f66d949f02`，reviewedAt `2026-08-05T16:02:51.8443399Z`。這是本機 baseline開窗 gate，
  不是 tracked portable release evidence；review JSON仍含 machine-local absolute path，修復前不得以
  `-RequireTracked`宣告 READY。
- 第十五次 aggregate-only baseline 已於 `2026-08-06 00:04:55.740380 +08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為
  `2026-08-07 00:04:55.740380 +08:00`。起始LINE inbound／text／outbound／decision trace皆103；trace
  outcome為SUCCEEDED 56、CLARIFICATION 44、FALLBACK 3、FAILED 0；task 1、legacy schedule 4、Calendar
  plan 2、time node 3、route risk 0、route operation preference 0、reminder rule／occurrence 0、intent issue 62、
  custom place 1、pending question 1、pending public-place browse 1、active HOME preference 0、pending Calendar
  draft 4、Flyway V112。快照未讀或保存私人原文、external ID、UUID、地址、payload或secret；closure必須
  elapsed ≥24h且LINE text inbound ≥123，其中新增20筆均為startedAt後本人真實LINE文字turn，synthetic
  probe／tests／provider benchmark不得計入。closure audit與portable tracked review evidence完成前維持非 READY。
- 2026-08-06 upstream sync gate：remote沒有`origin/master`，正式上游為`origin/main`；fresh fetch後由
  `3b01ba55fbb33467f1449190e840e06e01cc4990`前進至
  `5b652b88a89e1ee1336a60ce42b37fbca9da36c5`（PR #24 builtin place catalog）。目前signed HEAD
  `b32cb0d05db5feb78a5671380d3fc0836dd2b970`不是新main ancestor，merge-base為
  `132308e42d8a4c5dcd4929372a885fe1db66fd8a`。自signed HEAD起上游有15個paths與本lane dirty paths
  重疊；PR #24新增範圍直接重疊8個Conversation／Intent production、capability與test paths。初始metadata
  盤點只檢查dirty migrations而漏掉branch已tracked的Calendar V94；focused Flyway gate已推翻「無撞號」判斷。
  依`TR-PATH-OWNERSHIP-CONFLICT`
  `HARD_YIELD`，未執行merge／fast-forward／stash／reset或工作檔覆蓋，253項dirty state保持不變。
  第十五次baseline仍只屬目前未整合PR #24的production generation；若現在整合任何PR #24 production
  change，必須先永久invalidate本窗口，再重跑完整測試、runtime／LINE／Automatic review並另建baseline。
- 使用者已於2026-08-06選擇「立即整合並重建」。第十五次baseline永久標為
  `INVALIDATED_BY_ORIGIN_MAIN_PR24_INTEGRATION`；其startedAt
  `2026-08-06 00:04:55.740380 +08:00`、notBefore `2026-08-07 00:04:55.740380 +08:00`、起始
  inbound／text／outbound／trace 103、closure門檻123、runtime generation
  `06747128162542bcb086fd19e09c917f`及相關runtime／LINE／Automatic review receipt均不得再作release
  evidence。第一筆production變更只能在本標記後開始；整合後必須重跑全部適用hard gates並建立全新窗口。
- PR #24 migration collision repair：fresh fetch後working tree實際latest為V112；在
  `repo/flyway-sequence/main/V113`與本worktree source exclusive coordination均READY時，將新增且尚未執行的
  `create_system_place_catalog` migration由衝突V94移至next available V113。Flyway operation
  `f86209ca-032b-4219-adaf-64234e406769`、source operation
  `2d6c333d-66fd-4d57-8f94-1fec65879030`均已釋放；既有Calendar V94與其他migration內容未修改，版本重複為0。
- PR #24 local integration gate（2026-08-06）：87個merge paths已依three-way candidate精確套入既有dirty
  worktree，candidate／actual hash mismatch為0，branch與signed HEAD未移動。`PlaceIntentHandler`保留bundled
  catalog canonical自然語言路徑並加入explicit DB catalog ASK／ADOPT；ADOPT由public contract歸類為query＋
  entity-resolved evidence，custom Place零mutation。clarification inventory已由5更新為8個reviewed call sites，
  exact／cross-region／multipoint各維持一個typed問題。
- 重建證據：catalog／handler／capability／sealed focused 45／45、route neighbor 78／78、migration／FORCE RLS／
  actual-entry／REST／LINE 78／78、conversation safety 67通過後inventory修復10／10；repo／installed
  conversation skill normalized-newline SHA256 4／4相等，skill-creator官方`quick_validate.py` 2／2 valid。
  deterministic LINE replay原先誤打真實`api.line.me`，已用test-only `LineMessagingClient` mock隔離provider mutation，
  仍驗證120次signed webhook reply；60-sample warm P95為247 ms。Calendar create P95為139 ms，三組read-only
  actual-entry P95為609／258／380 ms，門檻均維持1.5秒。最終fresh
  `scripts\mvn-safe.ps1 -Clean test` 2,114 tests、0 failure／error、18 opt-in skipped PASS，耗時979.9秒。
  最後fetch確認`origin/main`仍為`5b652b88a89e1ee1336a60ce42b37fbca9da36c5`。runtime refresh、官方LINE E2E、
  7-capability Automatic review、portable tracked evidence與新24h／20本人真實LINE text baseline仍未執行，
  因此本lane維持非READY、不得commit／push／PR／merge。
- runtime rebuild blocker（2026-08-06）：正式`dev-start -SkipDispatcher`在建立ngrok ownership receipt時發現
  launch command（`http --url=<fixed host> 8080`）與validator唯一接受的`http://localhost:8080`互斥並rollback。
  改以正式`-NoNgrok -SkipDispatcher`只啟動main時，health已成功，但clean build的`git.properties`錯取primary
  root `307d9309…`／count 140，而target worktree為`b32cb0d…`／count 239，version gate判`STALE`後rollback。
  fresh DEV_RUNTIME與LINE_E2E receipts均為`ACTION_REQUIRED`；main/ngrok/Dispatcher皆未運行，Postgres／Redis
  healthy。規範指定的`dev-environment-report.ps1`在HEAD與origin/main都不存在，三項已登記tooling backlog。
  在修復合併並重新fetch前，不執行Automatic review、不建立baseline、不沿用任何舊generation或receipt。

## 12. 最終完成標準

- 所有 public outbound point經同一 final boundary，沒有 exception／model／provider reason直出。
- 所有 needs-input turn只有一個 next question；沒有 missing list或多欄位通用要求。
- 已回答 slot 不重問；correction、quote、parallel draft、restart與replay行為正確。
- 所有 executable Intent與特殊入口都有 machine-verifiable conversation contract。
- 成功、失敗、圖片、focus、REST、LINE、replay、provider-error均零 internal leak。
- Exact mutation、zero unintended mutation、idempotency、RLS、actor／workspace／quote boundary全綠。
- Repo／安裝版 conversation skill與Booking依賴規則完成同步，能阻止相同缺口再次被宣告完成。
- Focused、neighbor、actual-entry、root、sealed holdout、LINE E2E、24h／20 inbound全部通過。
- 所有 source／Maven／Docker／Flyway／LINE claims有 RELEASED receipt後，才可進 W11 product/state handoff。

## Tooling修復整合狀態（2026-08-06）

- `origin/main@0eba57a`（PR #25／#26）已以working-tree integration納入且未移動signed HEAD。ngrok launch
  contract與linked-worktree Maven identity直接測試均PASS；clean build實際產生`b32cb0d…`／commit count 239，
  舊primary-root identity blocker解除。fresh MAVEN為MATCH，但full clean test在Testcontainers啟動前遇到
  Docker daemon不可用，結果為2,114 tests、0 assertion failure、604 context errors、36 skipped，不能沿用為
  root PASS。managed Docker Desktop確認identity相符但180秒後`DAEMON_TIMEOUT`，依政策零backend／container／
  volume mutation。fresh DOCKER_TEST恢復MATCH並重跑clean root、runtime、官方LINE E2E、Automatic review前，
  仍不建立baseline且維持非READY。

## Docker恢復後final gate狀態（2026-08-06）

- fresh DOCKER_TEST／MAVEN MATCH後，完整clean root 2,114 tests、0 failure／error、18 skipped PASS，501.0秒；
  worktree Git identity維持`b32cb0d…`／239。runtime generation `2085802c312e4df4885171d704ee6bd5`已刷新，
  main UP、Postgres／Redis healthy、ngrok受管運行、Dispatcher DISARMED。fresh DEV_RUNTIME／LINE_E2E MATCH，
  官方LINE probe證明LINE→ngrok→Spring Boot connected；`dev-status`只因DIRTY/CURRENT判定與`dev-start`契約
  不一致而exit 1，不標整體PASS。
- fresh Automatic review六項capability均MATCH但openCount 3，outcome BLOCKED；正式external authority、
  sandbox Docker accepted-limitation lifecycle與manual monitor issue resolution尚缺。新24h／20本人真實LINE
  text inbound baseline尚未建立，舊baseline與generation evidence仍永久失效，本lane非READY。

## 2026-08-07 producer交付重驗

- `origin/main@68b54c8`的最新交付是producer-handoff automation與桌電route-benchmark evidence READY，並非
  calendar-w11環境review三項tooling修復。conversation production、safe-time reschedule及既有local root／runtime／
  LINE證據未新增變更；branch／signed HEAD及325項dirty ownership保持不變。
- host正式Automatic review再次證明六項實際capability均fresh MATCH，但outcome仍BLOCKED、openCount 4：
  sandbox Docker caller、缺正式external-provider authority、無MANUAL issue resolution，以及sandbox review
  Git ownership失敗後無法由fresh MAVEN snapshot關閉的caller-lifecycle issue。已更新target-worktree local evidence，
  不建立新24h／20本人真實LINE text inbound baseline，不commit／push／PR／merge或宣稱READY。

- 後續fresh fetch至`origin/main@9004d10`仍未出現上述四項修復；最新PR #34／#35／#37分別是sanitized
  evidence policy、桌電benchmark evidence與狀態文件。桌電benchmark為`INSUFFICIENT_EVIDENCE`且桌電Automatic
  review有5個open issue；GitHub沒有新的正確tooling PR。重開機前runtime／Docker／LINE receipts全部視為stale，
  在producer evidence與兩台Automatic review零blocker前仍不得開新baseline。

## 2026-08-07 route-origin tooling gate refresh

- `origin/main@a45fe8496259baa27f2085db865d0597f50a58b3`（PR #38）已安全整合至calendar-w11；
  branch、signed HEAD與325項核准dirty conversation hardening內容未變。此次只更新受限環境／handoff tooling與
  durable文件，未新增production response、route policy、schema或migration變更。
- 新contract可依本輪實際capability排除未使用的歷史Docker／runtime／LINE issue，並以typed resolution與
  scoped receipt驗證外部authority。focused tooling assertions已通過；固定clean-main inventory與深層Windows
  fixture仍是測試工具限制，已登記且不降低產品gate。
- 下一個安全gate為：fresh Docker與Maven receipts、`mvn-safe.ps1 -Clean test`、runtime refresh、官方LINE
  webhook E2E、Laptop Automatic review零blocker。桌電provider evidence穩定前不建立新的24小時／20筆本人真實
  LINE text inbound baseline；所有舊baseline、counts、generation與runtime receipt仍永久失效。

## 2026-08-07 laptop stable gate completed

- fresh clean root為2,114 tests、0 failure／error、18 skipped；runtime generation
  `ea1e9b3d1f154268901cd2dadb368bc7`以`VERIFIED_DIRTY`正確綁定calendar-w11 production-content
  fingerprint，官方LINE webhook E2E connected且`dev-status` exit 0。
- Laptop Automatic review以六項實際capability完成PASS，openCount=0；舊Docker／runtime／LINE issue只在
  matching current evidence下標為FIXED，未要求未使用的EXTERNAL_PROVIDER。review assertion PASS，但evidence
  尚未tracked，故不宣稱release merged／READY。
- route-origin產品碼未因本次tooling整合再變更；新的production baseline仍等待桌電provider evidence穩定與
  最終版本確認後才可建立，禁止沿用任何舊窗口、counts或runtime receipt。

## 2026-08-07 desktop evidence merged but benchmark insufficient

- `origin/main@699fc3e`（PR #39）已提供desktop Automatic review PASS/openCount 0與最新benchmark；對應四份
  evidence已新增至calendar-w11。TDX read-only樣本4/4成功，但Google零query、TDX freshness無timestamp、
  ARRIVE_BY typed unsupported，manifest／results均維持`INSUFFICIENT_EVIDENCE`。
- 因此筆電coding與LINE local gate可繼續，但整體release、READY與新24小時baseline仍fail closed；維持
  `TDX_PRIMARY`與walking-only首末哩，不推測Google品質、不把ARRIVE_BY改成DEPART_AT。

## 2026-08-07 desktop evidence integrity audit

- cases／manifest／results schemaVersion均為1，suite與caseSetHash一致；`cases.json`實際SHA-256
  `74efb2690754218529ed52204e1ed1710a7288ef3747c06757d9671746f34f8d`與宣告值相同，10個匿名case、
  4次external query、external mutation 0。secret／token／email／LINE ID／UUID pattern掃描為0，且manifest
  明示不含personal data或raw provider payload。
- `origin/main@699fc3e`在`docs/exec-plans/evidence/calendar-w11-h/`下仍只有
  `desktop-route-benchmark/**`；計畫要求的`sealed-holdout/**`與`public-response-evaluation/**`不存在。
  筆電不得自行產生後冒充獨立桌電evaluator evidence。

## 2026-08-08 desktop PR #41 evidence closure

- `origin/main@b0a230f14fcc6ca8f002d92947fe4b7300d48dcb`已合併PR #41；新benchmark cases
  SHA-256 `29fcbdd2bc0c055317c14a3b779368128a38bd26cc0212762d63701b1c42b88b`與兩份宣告相符。
  35次benchmark加3次sealed route query皆使用fresh matching provider-scoped READ_ONLY receipt，
  external mutation 0；正式benchmark conclusion為`SCENARIO_ROUTING`。
- sealed holdout evaluator於執行前凍結，case hash相符，3／3 PASS，無time-role change、fabricated
  route或raw payload。public-response evaluation 5／5 PASS，internal identifier／secret／absolute path leak均0。
  九份證據掃描無email、UUID、LINE ID、secret、private IP或absolute path。
- Desktop Automatic review為PASS、openCount 0，contract fingerprint為`855de50e9699b02fab6b427e`。桌電
  registered-worktree receipt不在筆電冒充target重跑；筆電只驗證Git tracking、schema、hash、privacy與結論。
- provider／獨立evaluator gate已解除。所有舊b aseline與runtime receipt仍失效；只有本版fresh
  runtime／官方LINE、Laptop Automatic review與新24小時／20筆真實LINE baseline可完成剩餘release gate。

## 2026-08-08 fresh runtime／LINE and Automatic review blocker

- runtime generation `994bfbb2991a4735afaca59b5db24cde`已用official `dev-start.ps1 -SkipDispatcher`
  刷新；Spring Boot UP，`VERIFIED_DIRTY`，PostgreSQL／Redis healthy，Dispatcher DISARMED。host
  DEV_RUNTIME／LINE_E2E receipts均MATCH，官方LINE probe exit 0且connected。
- Automatic review仍有1個caller-bound blocker：本輪早先sandbox DEV_RUNTIME因Windows Docker pipe權限產生
  `PREFLIGHT_CALLER_ACCESS_DENIED`；host的MATCH receipt不能代替sandbox解決。依policy不刪issue、
  不改StateRoot、不擴張Docker權限，並已登記tooling backlog。
- 在Git-verifiable tooling修復合併並重跑Laptop Automatic review PASS/openCount 0前，新24小時／20筆
  本人真實LINE baseline不啟動，本lane維持non-READY／non-MERGED。

## 2026-08-08 PR #42 schema v4 review receipt

- 已安全整合`origin/main@e4a41c18eb52eb1b2a0129d8eb4d9d533e212c9a`的PR #42 tooling contract；
  route-origin production binary未因此改變。focused tooling的managed-operation 18、report 14、lifecycle 16與
  service-version 20 assertions PASS；dirty lane完整umbrella只因test inventory尚未覆蓋387個既有test classes而停。
- 新受管runtime generation為`52515b82ab5249ee85b701924a1b0b93`，服務`VERIFIED_DIRTY`且官方LINE E2E
  connected；六項實際capability的schema v4 host preflight均fresh `MATCH`。
- schema v4 Laptop Automatic review（reviewedAt `2026-08-08T01:33:06.5846037Z`、contract
  `4e0e468fb5de192deb9f3419`）已typed修復DEV_RUNTIME／LINE_E2E的sandbox probe-only issue，但唯一
  DOCKER_TEST caller access-denied仍OPEN，outcome `BLOCKED`／openCount 1。不得刪issue、少報capability或
  提前啟動24小時／20筆本人真實LINE baseline；待Git-verifiable Docker participation tooling修復後重跑。

## 2026-08-08 PR #43 merge and ngrok ownership blocker

- PR #43精確head `24dfd4a384d929a047f89a05dce9942be393a070`已在required checks全綠後獲授權，
  merge commit為`714f8d60425a70282ad3ff2d082e473761be0c75`；10個tooling／test blobs已安全整合，
  focused 52 assertions PASS，route-origin production binary未改變。
- Docker daemon第一次bounded timeout後已恢復，matching stopped shared containers由official start安全恢復healthy；
  無container重建、刪除或volume mutation。但新啟動ngrok的ownership receipt在process command snapshot gate被拒絕，
  lifecycle已rollback，現在Spring Boot／ngrok not running、Dispatcher DISARMED。
- 不手動啟動ngrok、不略過ownership receipt、不重用舊runtime／LINE evidence。等待tooling producer交付
  Git-verifiable bounded snapshot修復後，才重建runtime、官方LINE、Automatic review與24小時／20筆baseline。

## 2026-08-08 PR #46 WMI-unavailable live retest

- 已安全整合PR #46 merge `9b8fe68331afc0ef8d7089b8be6aa3a1b3bc5588`，focused managed lifecycle
  28 assertions PASS且無receipt敏感command；route-origin production binary未改變。
- official start仍因ngrok identity snapshot在bounded window內未ready而rollback。host caller查詢自身PowerShell PID
  也只能取得`LIMITED_NATIVE`且無executable／command，故PR #46的WMI retry在此主機無法完成exact contract。
- 現在PostgreSQL／Redis healthy、Spring Boot／ngrok not running、Dispatcher DISARMED。不得繞過ownership、
  不沿用舊runtime／LINE evidence；待tooling alternate exact identity修復後再跑runtime、LINE、Automatic review與baseline。

## 2026-08-08 PR #47 mixed-receipt live blocker

- 已安全整合PR #47 merge `e27e93bdc6e2a521dbdfaab9a8790ce2c81d4c3e`；native exact與相鄰
  lifecycle 40 assertions PASS，本host same-caller native exact query實際PASS且不輸出敏感內容。
- official start跨過process identity後，在replay scan讀取沒有`action`欄位的合法舊／其他receipt時因StrictMode
  fail closed並rollback。不得刪receipt、換StateRoot或停用replay fencing；待typed mixed-receipt修復。
- PostgreSQL／Redis healthy，Spring Boot／ngrok not running，Dispatcher DISARMED；runtime／LINE／Automatic review
  與baseline均未完成，所有舊evidence仍不可沿用。

## 2026-08-08 PR #48 review-resolution idempotency blocker

- 已安全整合PR #48 merge `0902f2324d170e9db46002f9c918b9640f49cfa4`，focused 53 assertions PASS；
  official start已成功恢復`VERIFIED_DIRTY` runtime與官方LINE E2E，PostgreSQL／Redis healthy、Dispatcher
  DISARMED，route-origin production binary未因tooling整合改變。
- 六項host preflight均fresh `MATCH`；但在刷新managed-operation receipts後立即執行Automatic review，連續
  兩次於probe-only `DOCKER_TEST/PREFLIGHT_CALLER_ACCESS_DENIED` resolution回
  `matching OPEN typed environment issue does not exist`。這是同一typed issue重複關閉／非冪等ledger流程，
  不是Docker daemon或container未ready。
- 不刪除歷史issue或receipt、不更換StateRoot、不停用replay、不降低caller／generation契約。等待tooling
  producer提供Git-verifiable exactly-once與immediate-rerun regression；Laptop Automatic review達
  PASS/openCount 0前不啟動新24小時／20筆本人真實LINE baseline，所有舊baseline仍永久失效。

## 2026-08-09 PR #49 historical managed-evidence renewal blocker

- PR #49已合併為`3567c2e642678adcdad516d8cb16c999ecd264ac`；三個exact tooling blobs已安全同步，
  focused managed-operation review 34 assertions PASS。fresh SOURCE_WRITE／DOCKER_TEST／DEV_RUNTIME／
  LINE_E2E均`MATCH`，official `dev-start.ps1 -SkipDispatcher`成功，runtime為`VERIFIED_DIRTY`、
  PostgreSQL／Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。
- live schema v4 Automatic review仍在`Resolve-EnvironmentIssueByManagedOperation`以
  `matching OPEN typed environment issue does not exist` fail closed。canonical ledger證明
  DEV_RUNTIME與LINE_E2E的`PREFLIGHT_CALLER_ACCESS_DENIED`雖已是`FIXED／PROBE_ONLY`，其
  managed supersession evidence仍綁舊contract `4e0e468fb5de192deb9f3419`、舊generation
  `52515b82ab5249ee85b701924a1b0b93`與舊snapshot；在目前contract
  `786e05082c5e226466c84d7c`下 evidence invalid。review找到current fresh receipt後，PR #49只允許
  完全相同receipt／generation／snapshot的immediate rerun，未涵蓋合法歷史evidence更新。
- DOCKER_TEST另有真正OPEN的caller-access與shared-container歷史項，但流程在完成它們前已被上述renewal
  擋住。不得刪issue／receipt、換StateRoot、停用replay或放寬caller／generation／authority fence。
  已派發WSL Tooling producer補上old-contract／old-generation FIXED evidence到current fresh managed
  evidence的typed renewal與tamper／wrong-scope負向矩陣。Automatic review達PASS/openCount 0前不得啟動
  新24小時／20筆本人真實LINE baseline，`TR-CALENDAR-W11-MERGED`維持`PENDING`。

## 2026-08-09 PR #50 historical renewal closure

- PR #50 head `f2bc038102c20a3cad1a7b514cf20598eb031d49`已合併為
  `0f86167f7cc9675e507a03e1933351472441d3c5`；四個直接相關tooling／docs blobs已以hash fence精確同步，
  未覆蓋Calendar production dirty changes。host caller focused managed-operation test為64 assertions PASS。
- official managed restart後runtime source為`VERIFIED_DIRTY`，service generation為
  `9af14e7a90e34356b2a224eb48783965`；PostgreSQL／Redis healthy、官方LINE E2E connected、
  Dispatcher DISARMED。不得沿用本次重啟前的stale runtime或舊generation receipts。
- 六項schema v4 Automatic review於`2026-08-09T04:58:50.1712856Z`完成，contract
  `786e05082c5e226466c84d7c`；READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／
  LINE_E2E均`MATCH`，historical DEV_RUNTIME／LINE_E2E evidence renewal與真正OPEN Docker issues均依
  typed policy閉合，結果`PASS/openCount 0`。
- Automatic environment gate已解除，但本條不自行宣告W11 READY或`TR-CALENDAR-W11-MERGED`；仍須依完整
  release checklist確認baseline前置條件，並完成全新24小時／至少20筆本人真實LINE text inbound baseline。

## 2026-08-09 sixteenth monitoring baseline started

- 前置gate已重新稽核：最後production版本的focused／RLS／actual-entry／privacy／latency、fresh clean root
  2,114 tests、PR #41 `SCENARIO_ROUTING`／sealed holdout 3／3／public-response 5／5、current runtime／官方
  LINE與兩台Automatic review均具可追溯PASS evidence；PR #50後沒有新增production變更。
- 第十六次aggregate-only baseline於`2026-08-09 13:03:00.245014 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為`2026-08-10 13:03:00.245014 +08:00`，
  runtime generation為`9af14e7a90e34356b2a224eb48783965`。起始LINE inbound／text／outbound／decision
  trace皆106；trace outcome為SUCCEEDED 58、CLARIFICATION 45、FALLBACK 3、FAILED 0。
- 起始mutation／state aggregate：task 1、legacy schedule 4、Calendar plan 2、time node 3、route risk 0、
  route operation preference 0、reminder rule／occurrence 0、intent issue 63、custom place 1、pending question 1、
  pending public-place browse 1、active HOME preference 0、pending Calendar draft 5、Flyway V113。
- 快照未讀取或保存私人原文、external ID、UUID、地址、payload或secret。closure需elapsed至少24小時且
  LINE text inbound至少126；新增20筆均須為startedAt後本人真實LINE文字turn，synthetic probe、tests與
  provider benchmark不得計入。任何production修正會永久invalidate本窗口，必須refresh runtime並重建baseline。

## 2026-08-09 sixteenth monitoring baseline invalidated

- 狀態永久改為`INVALIDATED_BY_CONVERSATION_CONTEXT_TRANSITION_MISROUTING`。不得沿用startedAt
  `2026-08-09 13:03:00.245014 +08:00`、notBefore、106筆起始counts、closure門檻或runtime generation
  `9af14e7a90e34356b2a224eb48783965`作release evidence。
- Aggregate-only live diagnosis在不讀出或保存LINE原文、external ID、UUID、地址、payload或secret的前提下，
  確認counts已到inbound／text／outbound／decision trace各110。最新三個route turn皆validation PASS但
  execution outcome為CLARIFICATION、provider為NOT_REQUESTED；每輪建立新的`route.general-buffer`
  pending與Calendar draft。current pending確實指向Calendar draft，但root domain錯綁為active TASK，且同scope
  已有4份eligible route drafts，導致continuation未依pending workflow恢復、落回generic intake並重問。
- 使用者批准全服務通用規則：存在未完成操作時不得靜默改變上下文；明確延續、修改或開始新操作必須以公開
  操作名稱告知，無法判定延續或新操作時只問一個target-selection問題且zero mutation。明確quote優先、
  unrelated read-only interjection保留原pending；Booking／付款／取消等authority不得由context切換取得。
- 新production版本須先有failure-first、pending workflow精確綁定、context-transition、restart／replay／quote／
  parallel draft／RLS／actual-entry與zero／exact mutation evidence，再完成fresh root、runtime、官方LINE E2E、
  Automatic review，最後另建全新24小時且至少20筆本人真實LINE text inbound窗口。
- Migration reservation：fresh `origin/main=3153b06d18d829ff5150e76bc1d92cb0124f9469`、registered
  `calendar-w11` local latest為V113；本計畫仍是Calendar／Conversation／Flyway唯一production writer，桌電與
  Transport consumer均無schema claim，因此保留next available V114，只允許為pending context choice增加
  bounded typed question／safe-label欄位。不得保存LINE原文、候選新操作文字或generic JSON。

## 2026-08-09 context-transition implementation ledger

- Failure-first已證明無切換詞的第二個完整route request會被舊`route.general-buffer`誤消耗；修復後在
  handler／provider／Calendar mutation前改問單一`conversation.context-target`，draft維持1、plan 0、provider 0。
- V114在既有FORCE-RLS pending row新增nullable bounded `workflow_safe_label`與
  `interrupted_question_code`；不保存新操作文字、LINE原文、地址或JSON。明確延續恢復原question code；明確
  新操作先進`conversation.new-operation-content`，成功domain/focus transition時同交易完成舊pointer。
- `ConversationPendingQuestionService`以trusted workflow binding優先於無關active focus；所有route question依
  pending workflow ID解析。`PLAN_ROUTE_ITINERARY`納入typed START_OR_SWITCH，SWITCH／RESUME notice公開保留及
  目前操作名稱。
- Focused evidence目前為actual-entry 4／4、domain／service／catalog 10／10、含完整route actual-entry與pending
  RLS的neighbor batch 35／35 PASS。尚待restart／replay／quote／parallel matrix補強、capability／clarification
  inventory、skill validator、fresh root/runtime/LINE/Automatic review；不得視為stable gate。

## 2026-08-09 context-transition clean verification

- Context-target、typed direct answer、trusted quote、replay、parallel draft、materialized route draft與跨領域
  notice修正後，focused batch 54／54 PASS；capability catalog與clarification inventory 5／5 PASS。模糊切換仍在
  handler／provider／domain mutation前只問一題，明確回答舊問題則完成原pending pointer。
- Repo與安裝版conversation skill已完成normalized-newline 4／4 semantic MATCH；以UTF-8執行skill-creator官方
  `quick_validate.py`，兩份skill均回傳`Skill is valid!`。
- 非clean完整root regression為2,128 tests、18 skipped、0 failure；隨後正式fresh
  `scripts\mvn-safe.ps1 -Clean test`同樣為2,128 tests、18 skipped、0 failure。先前4 failures／7 errors已由
  production contract與明確new-operation fixtures修復，未降低assertion或放寬安全邊界。
- 尚須完成final production runtime refresh、官方LINE E2E與current-contract Automatic review；第十六次
  baseline仍永久失效，只有上述gate全綠後才能建立下一個全新24小時／至少20筆本人真實LINE text窗口。

## 2026-08-09 context-transition runtime gate

- Final production source已由official `dev-start.ps1 -SkipDispatcher`建立service generation
  `4da1b6e5eddf466596baafee6aaa58bc`；`dev-status.ps1 -ExternalLineProbe`回傳main
  `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、官方LINE connected、Dispatcher optional-unavailable／DISARMED。
- READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／LINE_E2E六項fresh preflight均`MATCH`。
  Laptop schema v4 Automatic review於`2026-08-09T11:24:46.3818248Z`完成，contract
  `786e05082c5e226466c84d7c`，結果`PASS/openCount 0`；正式assertion亦PASS。
- Runtime／environment gate已解除，但尚未以真人LINE完成本版代表情境與新aggregate-only baseline。第十六次
  baseline不得復活；下一個窗口必須綁定上述generation，並從新startedAt與新counts重新計算24小時／20筆門檻。

## 2026-08-09 seventeenth monitoring baseline started

- 第十七次aggregate-only baseline於`2026-08-09 19:30:02.221128 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為`2026-08-10 19:30:02.221128 +08:00`，
  runtime generation為`4da1b6e5eddf466596baafee6aaa58bc`。起始LINE inbound／text／outbound／decision
  trace皆110；trace outcome為SUCCEEDED 59、CLARIFICATION 48、FALLBACK 3、FAILED 0。
- 起始aggregate為task 1、legacy schedule 4、Calendar plan 2、time node 3、route risk 0、active route operation
  preference 0、reminder rule／occurrence 0、intent issue 66、custom place 1、pending question 1、pending
  public-place lookup 1、active HOME preference 1、pending Calendar draft 0、Flyway V114。
- 本快照未讀取或保存LINE原文、external message ID、UUID、地址、provider payload或secret，external query／
  product mutation皆為0。closure需elapsed至少24小時且LINE text inbound至少130；新增20筆須為本人真實LINE
  文字turn，synthetic probe、tests與provider benchmark不得計入。任何後續production修正會永久invalidate本窗口。

## 2026-08-09 seventeenth monitoring baseline invalidated

- 狀態永久改為`INVALIDATED_BY_CONTEXT_CHOICE_SHORT_ANSWER_AND_LEGACY_IDENTITY_FAILURE`。不得沿用本窗口的
  startedAt、notBefore、110筆起始counts、130筆closure門檻、runtime generation或Automatic／LINE receipts。
- 真人LINE依序證明「繼續」「新的」「全部清除重來」「繼續完成」落入`UNKNOWN`並產生4筆FAILED trace；
  context-target提示只能顯示「目前未完成的操作」。截至失效稽核，inbound／text 117、outbound 113、trace 117，
  outcome為SUCCEEDED 59、CLARIFICATION 51、FALLBACK 3、FAILED 4。
- Typed state證明current pending workflow ID仍精確連到route draft，測試期間新增route draft 0、Calendar plan 0、
  pending row 0；問題集中在bounded short-answer classification與舊row root-domain／safe-label recovery，不是provider
  或非預期business mutation。修復後必須建立fresh runtime／LINE／Automatic evidence與全新baseline。

## 2026-08-09 route-only operation lifecycle repair

- 本輪依使用者審查需求只啟用route lifecycle contributor。上層registry提供typed owner resolution與close協調；
  L1–L5其他能力只有rollout企劃，沒有production adapter或額外mutation authority。
- Failure-first兩案證明「繼續完成／新的」目前無法命中；修復後自然短回答可恢復或要求新操作內容。legacy
  `task`／空label pending由exact Calendar draft UUID恢復公開路線名稱，不以自由文字或LLM猜owner。
- Clear-and-restart在任何route question step只清除未完成route draft、目前pending與conversation focus；公開訊息
  命名操作並聲明既有行程未變。materialized plan保留，missing／ambiguous owner fail closed，replay exactly-once。
- 驗證目前為unit focused 24／24、route/pending/focus/RLS/actual-entry neighbor 61／61、capability/catalog/
  Calendar/LINE neighbor 42／42 PASS。actual-entry涵蓋「新的→繼續→全部清除重來」、跨actor/workspace隔離與
  zero unintended Calendar mutation。
- Conversation skill與三份reference已依skill-creator更新；repo／installed normalized-newline 4／4 MATCH，官方
  quick validator兩份皆valid。尚待fresh clean root、runtime／LINE／Automatic review與下一個全新baseline。

## 2026-08-09 route-only lifecycle clean and runtime gate

- Fresh clean root為2,141 tests、18 skipped、0 failure／error，受控runner 637.4秒並取得正式exit 0。timeout
  治理改為每個testcase／suite依自身歷史與功能增量校準，不得用固定整數或全包時間套用個別test。
- Fresh runtime generation為`5220661a25c44b7aac9775b3cee56cdd`；官方managed LINE E2E確認main
  UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected，Dispatcher維持DISARMED。
- READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／LINE_E2E皆fresh MATCH；laptop Automatic review
  reviewedAt `2026-08-09T14:16:32.4513843Z`，contract `786e05082c5e226466c84d7c`，PASS/openCount 0。
  第十七次baseline仍永久失效；本版真人LINE lifecycle代表案例通過後才可建立下一個全新窗口。

## 2026-08-10 route lifecycle actionable-resume and typed-staging repair

- Live typed metadata確認「繼續」雖找到 exact route draft，舊 public reply只命名操作而未重顯完整問題；下一輪因此落入 UNKNOWN 並覆蓋 visible question。failure-first 9 tests／2 failures後，route contributor改以 exact draft與 typed preference恢復完整 prompt／next-question。
- 使用者要求 agent 自行走完已知五步路徑後，三組 actual-entry一致揭露完整新路線在 context choice後被遺失、選「新的」仍重問內容。V115新增 nullable deferred workflow UUID pointer；route-only staging只建立 typed Calendar draft與 mode／time-role，pending不保存原文、地址或JSON，選擇前 provider／Calendar mutation為0，選新後才原子啟用。
- Focused unit 25／25 PASS（Maven 61.9秒）；三組五步 actual-entry 3／3 PASS（正式批次114.4秒，後續重跑63.2秒）；expanded lifecycle／RLS／actual-entry 73／73與capability catalog／clarification inventory精確重跑2／2 PASS。Fresh clean root 2,148 tests、0 failure／error、18 skipped、Maven 718.4秒、正式exit 0。
- Official runtime generation=`3e81c5ad1a644eceb9ca0516f7c76a50`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。Laptop schema v4 Automatic review reviewedAt=`2026-08-09T23:08:12.3285502Z`、contract=`786e05082c5e226466c84d7c`，六項capability MATCH、PASS/openCount=0，獨立assertion PASS。前一代runtime／Automatic與所有舊baseline evidence持續永久失效，須建立全新24小時／20筆本人真實LINE文字baseline。

## 2026-08-10 context-first operation resume gate

- route contributor透過共用上層resume contract提供typed public topic、current progress與單一next question；公開
  renderer固定「目前正在處理 → 目前進度 → 下一步」。三組known five-step actual-entry均驗證順序、question
  exactly once、restart／legacy recovery、provider exact count與Calendar zero mutation，未加入完整句子專用branch。
- focused 25／25（79.6秒）、三組actual-entry 3／3（81.1秒）、expanded lifecycle／RLS／actual-entry 75／75
  （99.7秒）、catalog／inventory 2／2（12.4秒）PASS。Fresh clean root 2,148 tests、18 skipped、0 failure／
  error、runner execution 767.8秒、正式exit 0。
- runtime generation=`59ceae2dc1cc4f62b4c01aea61b56819`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、
  官方LINE E2E connected、Dispatcher DISARMED。Automatic reviewedAt=`2026-08-10T01:19:38.5120722Z`、六項
  MATCH、PASS/openCount=0，assertion PASS。先前runtime／Automatic與baseline全部失效，須重建24h／20筆baseline。

## 2026-08-10 standalone transport interval clarification gate

- 產品語意已固定：明確起訖與出發／抵達時間角色的standalone交通規劃，其行程區間由provider duration／ETA
  推導，不詢問「活動預計多久」或「幾點結束」；只有另行typed為活動本體的`ACTIVITY_WITH_TRANSPORT`可詢問
  活動時長。route-shaped interpreter failure只可詢問缺少的交通時間／時間角色，且Calendar mutation為0。
- Failure-first三個常用規劃句型原先2案落入`route.activity-duration`；修復後focused 6／6（90.4秒）、route
  neighbor 52／52（85.8秒）、capability catalog／clarification inventory 2／2（13.4秒）PASS。未將不含規劃動詞的
  純敘述句硬編碼成route intent，避免擴張語意邊界。
- Google Routes credential已在本worktree的未追蹤secrets設定中以既有Places key作typed alias，不顯示或提交
  secret；fresh provider-scoped read-only query成功回傳1條路線、1720秒／9624公尺，external mutation為0。
  provider policy仍為`TDX_PRIMARY`，未以單次測試改成Google預設。
- Fresh clean root為2,150 tests、18 skipped、0 failure／error，runner execution 822.8秒、正式exit 0；相較同scope
  前次767.8秒增加55秒（約7.2%），仍在依歷史與本次功能增量選定的900秒execution上限內。focused→neighbor→
  catalog／inventory是time-to-first-failure順序，不是測試狀態相依；final clean root仍完整重跑全部案例。
- Official runtime generation=`a46d5978e369427387ce047e305b8b46`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／
  Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。六項fresh capability皆MATCH；Laptop schema v4
  Automatic review reviewedAt=`2026-08-10T03:13:18.8333414Z`、contract=`786e05082c5e226466c84d7c`，
  `PASS/openCount 0`且獨立assertion PASS。所有較早runtime／Automatic與baseline持續永久失效；尚未建立新24h／
  20筆本人真實LINE文字baseline，`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-10 eighteenth monitoring baseline started

- 第十八次aggregate-only baseline於`2026-08-10 11:21:05.683068 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為`2026-08-11 11:21:05.683068 +08:00`，綁定
  runtime generation `a46d5978e369427387ce047e305b8b46`與本節上方current-contract Automatic evidence。
- 起始LINE inbound／text均127、outbound 123、decision trace 127；trace outcome為SUCCEEDED 62、
  CLARIFICATION 58、FALLBACK 3、FAILED 4，Flyway V115。closure需elapsed至少24小時且LINE text inbound至少147；
  新增20筆須為startedAt後本人真實LINE文字turn，synthetic probe、tests與provider query不得計入。
- 快照未讀取或保存LINE原文、external message ID、UUID、地址、provider payload或secret；external query／product
  mutation均為0。任何後續production修正會永久invalidate本窗口；`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-10 eighteenth monitoring baseline invalidated

- 第十八次窗口永久標為`INVALIDATED_BY_ORPHAN_ROUTE_PENDING_SILENT_LINE_FAILURE`；不得沿用其startedAt、
  notBefore、起始127／123／127 counts、closure 147、runtime generation或Automatic receipts。
- startedAt後三筆真人LINE文字turn「繼續」、完整新路線要求與詢問下一步均產生`UNEXPECTED_INTENT_FAILURE`，
  只有inbound與FAILED trace而沒有outbound。診斷證明舊`route.activity-duration` pending錯綁不相干task focus，
  route continuation以該workflow查Calendar draft時得到`NotFoundException`；provider尚未被呼叫。
- 修復必須同時阻止cross-domain pending ownership、把missing／expired legacy owner轉為typed safe recovery，並確保
  LINE unexpected failure有不洩漏內部資訊的terminal reply。修復完成前baseline與READY均fail closed。

## 2026-08-10 orphan route pending lifecycle repair verification

- 根因確認為三段式失效：未綁定route question錯誤繼承active task focus；route answer以錯誤workflow直接取得
  Calendar draft並拋出`NotFoundException`；只取消pending pointer會留下舊PENDING route draft，下一次新要求仍可能
  選到兩個候選。Google／TDX在失敗turn均未被呼叫，因此不是provider或credential問題。
- 修復維持上層typed lifecycle邊界：route question不得繼承非route owner；missing owner使用optional lookup；短句
  `繼續`／`繼續完成`／`傳什麼？`會先公開目前上下文已失效與下一步；完整新路線會在同一turn取消舊pointer、
  丟棄唯一未完成route draft並繼續provider流程。共用lifecycle只新增typed current-unfinished hook，目前僅route
  contributor實作，未擴張到其他功能。
- failure-first為27 tests中5 failures／1 error（105.7秒）；修復後actual-entry 16/16（179.4秒）、focused
  27/27（67.5秒）、lifecycle neighbor 11/11（51.9秒）、Calendar actual-entry neighbor 19/19（85.1秒）PASS。
  驗證包含三個常用續作語句、cross-domain owner、missing draft不拋例外、exactly-one新route draft、provider
  invocation與zero Calendar plan mutation。依使用者指定，真人LINE確認前不跑root clean。
- 官方`dev-start -SkipDispatcher`完成；`dev-status -ExternalLineProbe`確認版本
  `239-b32cb0d05db5-dirty`、Spring Boot UP、source VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、
  Dispatcher optional-unavailable。此production修正後先前runtime／Automatic／baseline evidence均不得沿用；待真人
  LINE路徑確認後才進下一個release gate。

## 2026-08-10 explicit route time and pending reactivation follow-up

- 真人LINE再次證明完整句子已選中`PLAN_ROUTE_ITINERARY`，但model回傳不符合route script contract，trace為
  `ROUTE_INTERPRETATION_INCOMPLETE`，服務錯問已提供的出發時間。另因cancel與新question記錄在同transaction，
  未flush的舊task pending row被重新ask並復活；這不是Google／TDX失敗，provider尚未被呼叫。
- 修復加入Java typed explicit-route time policy，支援相對日搭配「九點／九點半／9:45」等組合；deterministic
  route router已確認明確起訖與規劃語意時，即使model為UNKNOWN／錯型別，仍以Java grounded time建立route command，
  不重問相同slot。pending cancel改為flush後才建立新pointer，禁止舊cross-domain owner復活。
- focused XML 18/18 PASS（Java time 3、pending actual-entry 2、transition 13）；runner在XML完成後收尾逾時，
  不計為test failure。精準route actual-entry 5/5 PASS（126.5秒），涵蓋原始真人句型、UNKNOWN model、typed time、
  `route` owner、zero unintended Calendar mutation。依使用者要求仍未跑root clean；需重建runtime後再真人LINE確認。

## 2026-08-10 route conflict context and full Google Maps directions link

- 衝突公開回覆改為列出每個既有行程名稱、boundary time、可用銜接分鐘、required travel與至少不足分鐘；
  direct overlap、previous-only與next-side／both-side分別保留typed question code。後方有風險時不再提供無法解決
  問題的「往後安排」，錯誤回答會重顯完整原因與keep-only問題，Calendar時間與provider invocation均不變。
- 既有兩個Google Maps地標連結後追加一個directions URL。只有起訖都為可公開typed endpoint且座標有效時輸出，
  並由verified `TravelMode`映射driving／walking／two-wheeler／transit；HOME、推定context、legacy或任一私人端點
  會抑制完整路線網址。網址不使用API key、不呼叫Google Routes、不取代TDX evidence。
- failure-first focused 18案中1案精準因缺少directions URL失敗（52.0秒）；修復後unit 23/23（53.9秒）、
  Calendar actual-entry 20/20（93.7秒）、preflight/lifecycle neighbor 9/9（13.7秒）、standalone actual-entry
  17/17（90.3秒）PASS。replay文字一致、explicit exactly-one plan、HOME privacy與zero extra mutation均通過。
- repo與installed conversation skill normalized-newline 4/4一致，skill-creator官方quick_validate 2/2 valid。
  本次尚未跑fresh root clean、重建runtime／LINE或Automatic review；generation
  `71fa229818634462877fd313e5c06b6b`與其舊Automatic evidence持續永久失效，未啟動新baseline。
- 嘗試重建時official `dev-start -SkipDispatcher`寫入generation
  `befb5988345245d9ac5b71518d2b579a`並exit 0，但緊接的official ExternalLineProbe顯示所有runtime層不可見且
  LINE disconnected；此generation不得作runtime evidence。產品測試仍PASS，live gate交由獨立tooling producer
  修復launcher persistence／false-success後再重跑，期間不啟動baseline。

## 2026-08-11 committed-itinerary add-on lifecycle repair

- 真人LINE顯示已建立的「前往高鐵桃園站」因尚待出發提醒，被共用context transition誤稱為未完成操作並攔截
  下一個完整指令；開車停車與計程車等車偏好也在provider與Calendar materialization前詢問，違反主流程優先。
- 修復後provider evidence與exactly-one Calendar plan先完成，再依交通模式詢問停車／等車，最後詢問出發提醒。
  回答附屬問題只更新typed preference／reminder，不重查provider或重建plan；新的完整START_OR_SWITCH operation
  會完成未答附屬pointer並直接執行，不進`conversation.context-target`。真正provider failure與必要gate仍維持
  unfinished lifecycle。context提示同步改為「這次指令是要繼續這個操作，還是開始新的操作？」。
- failure-first為34 tests中4 failures（78.7秒）；修復後focused 35/35（68.0秒）及route neighbor 48/48
  （42.5秒）PASS，涵蓋公開提示、exactly-one plan、zero reminder before consent、provider exact count、replay與
  真正unfinished provider gate。依使用者要求未跑root clean。
- official runtime generation=`69f0d7f63d6446fe94fc12123aa399af`；Spring Boot UP／VERIFIED_DIRTY、PostgreSQL／
  Redis healthy、Dispatcher DISARMED，獨立`dev-status -ExternalLineProbe`確認LINE→ngrok→Spring Boot connected。
  本production修正使先前baseline／Automatic evidence永久失效；真人LINE確認前不得啟動新baseline。

## 2026-08-11 typed current-operation cancellation repair

- 真人LINE中「取消」與「取消這次的行程建立」都落入`UNKNOWN`，服務回generic unknown；第二次unknown question又
  覆蓋原`route.direct-overlap` pending context。根因是取消依賴LLM偶然輸出`CANCEL_CONTEXT`，且pending writer未
  保護capability-owned question。
- 修復在共用context-transition gate加入bounded typed current-operation cancel。只有lifecycle能精確解析owner時
  才丟棄未完成route draft並取消pending／focus；公開說明已取消的路線主題與「已建立的行程沒有變更」。明確
  「取消明天九點的行程」不被攔截，仍交由committed Calendar cancellation。`intent.unknown-*`不得覆蓋既有typed
  pending，因此任何步驟仍可取消、補值或繼續。
- failure-first focused 21案中4案精準失敗：三個常用取消語句沒有結果、unknown覆蓋pending。修復後focused
  21/21 PASS；standalone route完整Intent actual-entry 20/20 PASS（70.7秒），其中三個常用取消語句均驗證replay、
  draft=`DISCARDED`、pending=`CANCELED`、既有Calendar plan exact count不變、provider只呼叫一次。
- 依使用者要求本次小範圍修改不跑root clean。production source已變更，generation
  `69f0d7f63d6446fe94fc12123aa399af`及舊Automatic／baseline evidence永久失效；需重建runtime並完成真人LINE
  代表案例後才可建立新baseline。

## 2026-08-11 immediate route time and unconfirmed-origin repair

- 真人LINE輸入「幫我規劃待會3:30點從公司到高鐵桃園站」，trace已選`PLAN_ROUTE_ITINERARY`但以
  `INVALID_COMMAND`回`route.time`。第一根因是deterministic explicit-time policy只接受相對日期／日期，未接受
  `待會`等未來語意；第二根因是目前沒有已確認的「公司」Place，route origin解析拋出的地點錯誤被上層一律
  誤映射為時間澄清。provider與Calendar mutation均為0。
- 修復以注入Clock解析`待會`／`稍後`／`等一下`／`晚點`加明確時刻；無period的1–12點取最近未來occurrence，
  明確24小時／period則必須仍在未來。裸時刻無日期或只有模糊相對詞仍不猜。route destination維持strict；只有
  explicit route origin可先以null typed slot保存draft，公開說明已記下時間並只問一個已確認出發地，補值後沿用
  `route.origin-context`續作，不重問時間。
- failure-first policy 9案中新增5案精準失敗；首次actual-entry再揭露未確認origin被錯映射為`route.time`，未降低
  assertion。修復後policy＋Calendar完整actual-entry 31/31 PASS（89.2秒），route router／conversation neighbor
  26/26 PASS（12.1秒）。原始句型驗證timed start已保存、plan=0、provider invocation=0及單一typed下一題。
- 依使用者要求本次小範圍修改不跑root clean。上一個runtime generation與Automatic／baseline evidence永久失效；
  必須重建runtime並由真人LINE重測原句後才可建立新baseline。

## 2026-08-11 route origin cancellation identity repair

- 真人LINE建立`route.origin-context`後連續兩次傳「取消」，兩次都被地點slot handler消耗並重問出發地；pending
  維持PENDING。對照前一個`route.time`取消成功，根因不是LINE送達或cancel詞彙，而是explicit-unconfirmed-origin
  草稿在問題送出前尚未保存`routeTimeRole`，route lifecycle contributor依typed identity fail closed。
- 修復在任何standalone route必要澄清（endpoint、origin、mode、time）前先以既有
  `prepareStandaloneRouteRequest`保存bounded mode/time-role identity；不呼叫provider、不建立Calendar。取消仍走同一
  owner-resolved lifecycle，不放寬為question-code或全文特判。
- failure-first actual-entry 1/1精準失敗為`CLARIFICATION_NEEDED`而非`CONTEXT_UPDATED`；修復後同案1/1 PASS
  （76.8秒），驗證replay、draft=`DISCARDED`、pending=`CANCELED`、plan=0、provider=0。完整Calendar actual-entry＋
  context transition／route contributor／operation lifecycle 50/50 PASS（51.9秒）。依使用者要求未跑root clean。
- 本production修正使generation `91ab62452aec4a04811a091a5c15029e`與舊Automatic／baseline evidence永久失效；
  重建runtime並完成真人LINE取消代表案例前不得啟動新baseline。
- 重建後稽核發現眼前真人pending本身由修正前版本建立，精確狀態為PENDING／STANDALONE_TRIP／time-role null／
  NOT_REQUESTED；只修新寫入順序仍無法讓這筆立即取消。補充legacy owner contract：`routeTimeRole`或typed
  `routeJourneyKind`任一存在即可在exact workflow lookup下認定route，兩者皆缺仍fail closed，不信question code。
  第一次test compile因package-private enum不可見而未進測試；改以public boolean view後，真正failure-first為9案中
  1案resolve empty。修復後legacy contributor＋context transition＋原始actual-entry取消28/28 PASS（77.3秒）。

## 2026-08-11 Route→Place child lifecycle與共用狀態查詢

- 使用者要求建立路線途中可進入建立地點子流程，且任何步驟都能用泛用句詢問目前處理項目／進度。範圍依
  審查邊界只實作Route→Place，其他能力留在分批rollout。
- failure-first 4／4失敗：三個status語句回空，未知origin敘述仍回`route.origin-context`。production改以typed
  operation parent、leaf/tree close與read-only status renderer處理；V116 child只保存exact parent revision、
  bounded alias/query與provider-resolved candidate，FORCE RLS且不保存raw LINE或generic JSON。
- 「儲存並繼續」exactly-once建立Place／alias後恢復route；「只用這次」不保存Place；plain取消回父route；
  明確取消整個行程才關閉整棵未完成tree。provider不可用時不再中斷webhook，而是保留父route並只問一題，
  Place／Calendar mutation皆為0。
- expanded focused最新58／58 PASS（67.8秒）；lifecycle／Place neighbors 60／60 PASS（14.2秒）；RLS／migration／
  catalog／clarification inventory 14／14 PASS（44.2秒）。repo與installed conversation skill均通過official
  quick validation，normalized-newline 4／4 MATCH。
- official `dev-start -SkipDispatcher`於88.1秒成功，generation=`69c5a96fdab74d2a9490e1927adc7b27`；Spring Boot
  `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、Dispatcher DISARMED、LINE connected。第一次sandbox status
  probe因caller access與外部receive中斷失敗，正式caller同generation重跑45.4秒PASS，未重啟服務。舊runtime、
  Automatic與baseline永久失效；尚待真人LINE代表案例與最後clean root，不得宣告READY或啟動baseline。

## 2026-08-11 colloquial operation-status recognition repair

- 真人LINE「處理到哪了」未進入read-only status interrupt。根因是policy要求`現在／目前`開頭且只接受
  `哪裡／哪個`等長疑問詞，省略前綴的口語短句被交給一般意圖辨識。
- failure-first同一selector 4案中reported phrase 1案精確失敗。修復以bounded semantic composition支援
  `處理到哪／做到哪／進度如何／在處理什麼／在忙什麼`，以及帶`現在／目前／當前`的操作疑問；未加入
  reported full-sentence branch。`處理公司地址／進度報告明天交／哪個項目需要處理／如何處理這個地點`
  四個neighbor均不被攔截。
- 完整context transition 30／30 PASS（33.1秒）；原句Route→Place actual-entry 1／1 PASS（71.6秒），公開
  回覆仍包含父主題、子步驟、保留／未完成資訊與原問題，pending revision不變，Place／Calendar mutation 0。
  本次production修正使先前runtime、Automatic與baseline evidence永久失效；依使用者規則真人LINE確認前不跑
  root clean。
- official stop因Spring與ngrok各自的bounded verification逾時保留ownership；多次managed replay後ngrok仍為
  exact orphan。依使用者既有授權且在official proof已重驗PID／generation／command fingerprint、無競爭session後，
  只對該exact ngrok PID執行一次`Stop-Process`，再由official `dev-stop`於8.3秒完整收斂，未碰其他程序或資料。
  fresh `DEV_RUNTIME`／`LINE_E2E`皆MATCH；`dev-start -SkipDispatcher`於83.5秒成功，新generation=
  `79801797f25545119ea717a189b03af4`，跨caller external probe 41.3秒PASS：Spring `UP/VERIFIED_DIRTY`、
  PostgreSQL／Redis healthy、Dispatcher DISARMED、LINE connected。

## 2026-08-11 Route→Place Google Maps input hardening

- DETAILS現在同時接受地點名稱、地址與Google Maps連結；完整網址與`maps.app.goo.gl`短連結共用typed URI
  resolver，短連結最多四跳且每一跳重驗exact Google Maps host、HTTPS、無userinfo與無自訂port。
- 有座標的完整／展開後網址不需要Places key；非Google URL或hostile redirect fail closed。child只保存解析後
  candidate name／address／coordinates，原始URL不進pending row、公開回覆或log；確認前Place／Calendar mutation 0。
- focused首輪23案中20 PASS，3案為既有公開文字assertion與新fixture時間格式不符，非production failure；修正
  測試契約後Route→Place三條actual-entry 3／3 PASS（69.2秒）。Google client／Place service的20條安全與鄰近
  測試在首輪已PASS。repo／installed conversation skill官方validator 2／2 valid、normalized-newline 4／4 MATCH。
- expanded Google client／Place／Calendar actual-entry／FORCE RLS 60／60 PASS（71.4秒）；capability catalog與
  clarification inventory 2／2 PASS（8.4秒）。第二constructor一度造成Spring選擇不明，已用production
  constructor明確注入修復；plain取消neighbor同步驗證leaf-only cancel並保留父route。
- 依使用者要求未執行root clean。fresh runtime generation=`5c61d35c203a4d59a8fa7531cbb812cc`；official
  `dev-start -SkipDispatcher` 90.4秒PASS，external LINE probe 55.6秒PASS：Spring `UP/VERIFIED_DIRTY`、
  PostgreSQL／Redis healthy、Dispatcher DISARMED、LINE connected。舊runtime、Automatic與baseline不得沿用。

## 2026-08-11 route origin phrase與一次性目前位置hardening

- 真人案例把`從公司出發`當成完整Place alias。修復改在Route→Place boundary做typed normalization，不以完整
  句子hard-code；涵蓋介系詞、動作、引號、空白、口語方位與出發地／起點欄位式語法，並保留含交通字樣的
  真實地點名稱。
- `我目前的位置／現在的位置／這裡／這邊／此處`改為`TRANSIENT_CURRENT_LOCATION`。無Maps link時保留原本
  時間與目的地，只問一次本次位置連結；有link時直接解析為`EXPLICIT_CURRENT_TURN`，不顯示建立永久地點或
  儲存選項，不建立Place／alias／HOME，raw URL不落資料。
- 首輪policy 50案有2個語意failure（`公司這邊`誤判current、`我的出發地`前導剝除過度），收緊邊界後又發現
  1個帶括號link案例，修正為link存在時才允許bounded current token。最終route grounding＋policy 59／59 PASS
  （52.4秒），19條actual-entry 19／19 PASS（58.8秒），完整Calendar actual-entry 51／51 PASS（63.1秒）。
  依使用者要求本小增量未跑root clean；任何舊runtime、Automatic與baseline evidence失效。

## 2026-08-12 Route→Place stage-bound context與retain-switch repair

- 真人LINE證據顯示OFFER無法接受「好，公司就是內湖富邦大樓」與直接Maps link；更嚴重的是
  `OfferChoice`以substring `建立`把完整「幫我建立…聚餐的行程」吞成建立地點同意，child由OFFER錯進DETAILS。
- failure-first 7案為2 failures／2 errors：自然宣告與Maps link離開child、新行程沒有進context choice。修復改為
  bounded exact offer choice、typed地點形狀／Maps輸入與stage compatibility；新增non-route Calendar draft
  contributor，只stage typed draft且不materialize Calendar。
- 公開選項統一為「保留目前進度並開始新的操作」，不用「離開並儲存」。不相容輸入會說明目前父主題、
  建立地點子階段、資料未修改、可接受答案與保留切換方式；不會把異議／狀態詢問保存為地址。
- 修復後原7案7／7 PASS（81.1秒）；擴充自然宣告／直接名稱／Maps link、新操作、狀態與異議後12／12 PASS
  （88.4秒）；context lifecycle neighbors 98／98 PASS（37.4秒）。所有案例provider與Calendar mutation維持契約值。
- 本次production修改使先前runtime、Automatic與任何baseline evidence失效。尚待完整actual-entry、fresh runtime／
  LINE代表案例及使用者指定的階段性clean root；完成前不得宣告READY或啟動release baseline。
- 完整Calendar actual-entry首輪56案有14個舊公開文字assertion失敗，狀態與mutation無失敗；同步新版
  `已儲存／這次要怎麼處理／保留`契約後56／56 PASS（104.0秒）。capability catalog與clarification inventory
  5／5 PASS（10.9秒），inventory同步Calendar clarification call sites 24→25。
- conversation skill的SKILL、scenario design、acceptance rubric與generalization checklist已同步repo／installed；
  normalized-newline 4／4 MATCH，skill-creator官方quick validator 2／2 valid。
- 第一輪階段性clean root為2,312 tests／18 skipped／3 failures／0 errors（676.3秒）；三案皆為known five-step
  parameterization仍期待舊「繼續這個操作」文字，其他2,309案無失敗。同步使用者批准的「繼續目前操作／保留
  目前進度並開始新的操作」精確assertion後，該三案3／3 PASS（91.4秒），尚待第二輪clean root正式全綠。
- 第二輪全新clean root正式2,312 tests／18 skipped／0 failures／errors PASS，Maven execution 691.7秒；這是
  下一輪相同scope的新時間基準，queue wait未混入。尚待fresh runtime與官方LINE E2E，release仍非READY。
- Fresh `DEV_RUNTIME`／`LINE_E2E` MATCH；managed lifecycle將舊ngrok精確收斂後，official
  `dev-start -SkipDispatcher`於133.3秒成功，generation=`bad2fa0f2f08418bb1062fedcd4380df`。ExternalLineProbe
  74.8秒PASS：Spring Boot UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。
- Laptop schema v4 Automatic review reviewedAt=`2026-08-11T22:20:01.7385341Z`、contract=
  `786e05082c5e226466c84d7c`，六項capability MATCH、PASS/openCount=0。獨立`RequireTracked`正確拒絕目前尚未
  commit的evidence；stable gate前不為此先commit。尚待真人LINE代表案例，通過前不啟動24h baseline。

## 2026-08-12 Route→Place child completion parent-resume repair

- 真人LINE在CONFIRM子階段回答「儲存地點」後只收到泛用父層結果。failure-first以競爭的typed
  `ACCEPT_CONTEXT`重現4／4 failures：三個常用儲存語句未被child接住，回覆「目前沒有可接受的行程提案」，
  證明輸入逃離Route→Place lifecycle。
- 修復只擴充CONFIRM階段的bounded SAVE choice，不改handler或模型判斷；`儲存地點／保存這個地點／把這個
  地點存起來`皆先完成child、把typed origin寫回exact parent，再由父路線completeness gate續作。
- 父流程完整時exactly-one Place／alias／Calendar plan，公開回覆包含TDX路線、兩端Maps與整段directions，並
  進入出發提醒附屬問題；provider不可用時Place建立、parent維持PENDING、Calendar plan=0，停在父流程重查選項。
- failure-first修正後4／4 PASS（145.3秒）；SAVE replay／ONE_TIME／leaf cancel／whole-tree cancel鄰近8／8
  PASS（67.8秒）；完整Calendar actual-entry 60／60 PASS（94.0秒）。本次小修正依使用者要求尚未跑clean root。
  production變更使generation `bad2fa0f2f08418bb1062fedcd4380df`、舊Automatic與baseline evidence失效；須
  fresh runtime／LINE代表案例確認後，才執行階段性clean root與後續release gate。
- 第一次official start因舊ngrok ownership receipt與durable generation不一致fail closed並rollback Spring；
  `dev-status`將PID 904判定為`ORPHAN_EXACT_RECONCILABLE`。整體stop bounded verification逾時後，受限
  `stop-managed-worktree-process`重新`ValidateOnly`為READY／`PROVEN_NOT_RUNNING`，再以相同exact proof正式
  收斂，未碰其他process或資料。第二次official start 215.5秒PASS，新generation=
  `8ebb257546354822a46baae0e9fc2f21`；ExternalLineProbe 68.0秒PASS，Spring UP／VERIFIED_DIRTY、PostgreSQL／
  Redis healthy、LINE connected、Dispatcher DISARMED。尚待真人LINE代表案例，未啟動baseline。

## 2026-08-12 numbered typed choice contract and Route→Place rollout

- 真人LINE的Route→Place CONFIRM把三個不同保存語意擠在單句。使用者拍板為專案規則：兩個以上操作一律
  `1. 2. 3.`編號、選項間空行，每項公開資料保存／mutation影響；內容與答案解析同源，不得在回答方法hard-code。
- 新增共用`PublicConversationChoice`、`PublicConversationChoiceQuestion`、numbered renderer與
  `IntentResult.choiceNeeded`；上層`ResumeQuestion`可承載同一typed choice question，正常提問、狀態查詢恢復與
  replay共用catalog。首個catalog只落地Route→Place SAVE／ONE_TIME／CANCEL，其他capability不在本輪批量改寫。
- failure-first真人樣態1／1 failure（103.7秒），證明舊回覆仍為行內選項。renderer／catalog 11／11 PASS
  （69.3秒）；Route→Place正常與resume、SAVE三語句、ONE_TIME、CANCEL、父流程complete/incomplete 8／8 PASS
  （114.0秒）；共用context-transition neighbors合計41／41 PASS（21.1秒）。
- AGENTS、current decisions、architecture、test strategy與conversation skill四份規範已同步新契約。此次production
  修改使generation `8ebb257546354822a46baae0e9fc2f21`及其runtime／LINE evidence失效；尚待skill validator、
  semantic sync、fresh runtime／LINE真人案例與使用者指定的階段性clean root，未啟動baseline。
- 完整Calendar／catalog／inventory批次65案中60 PASS，5案皆為仍期待舊行內文字的public contract assertion，
  domain state與mutation無失敗。final readability會把縮排說明轉成次層bullet，因此renderer收斂為每項單行
  `N. 類別：影響`且選項間空行；更新五案後精準16／16 PASS（124.8秒）。
- conversation skill repo／installed normalized-newline 4／4 MATCH；skill-creator官方quick validator起初因系統
  Python缺PyYAML及cp950讀UTF-8失敗，固定PyYAML 6.0.2只安裝到target暫存並啟用process-local UTF-8後，兩份
  2／2 valid。未修改系統Python或提交validator dependency。
- 使用者將風險分層測試與早期runtime定為全專案規則：focused／required neighbors通過可先部署真人測試，
  clean root由風險決定；但commit／push／非Draft PR前仍須完成scope所有正式gate與必要clean root，exact head
  不得沿用舊證據。規則已寫入AGENTS、current decisions與test strategy。
- Early runtime generation=`7015a7ebb075447c8f6859e011b2edd3`；official start 199.8秒PASS，ExternalLineProbe
  67.3秒PASS：Spring UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。
  現在開放真人LINE代表案例；這是early deployment，不是PR/release完成，baseline未啟動。

## 2026-08-12 Route operation completion claim gate

- 真人LINE orphan evidence顯示Place child完成後，父draft雖已被舊版標成`MATERIALIZED`，實際仍只有單點plan、
  provider invocation=0；舊回覆卻宣稱「先前已建立」。failure-first 1／1在105.2秒精確重現。
- 新增共用typed operation completion gate，首批只啟用Route contributor。`MATERIALIZED`只代表committed資料；
  gate另查核actor/workspace-owned draft、active plan、verified interval、exactly-two endpoint nodes、provider
  evidence及required Place child均完成，才授權「已替您安排」。完成後的可選出發提醒不屬於前置條件。
- orphan修復沿用同一plan：child地點接回parent後呼叫provider，原start node原子修正為origin，再新增唯一end node；
  materialized plan排除自身preflight，不建立第二筆plan，也不忽略真正其他行程。
- failure-first修正後1／1 PASS（100.8秒）；completion gate matrix 5／5 PASS（49.7秒）；數字／三種常用文字、
  provider unavailable、one-time及replay actual-entry 7／7 PASS（148.8秒）。repo／installed skill normalized
  semantic 4／4 MATCH，skill-creator官方validator valid。尚待security-neighbor、fresh runtime／真人LINE與依範圍
  決定的階段性root；任何舊runtime、Automatic與baseline evidence失效，release維持非READY。
- security/persistence neighbors 19／19 PASS（71.8秒）、conversation service 23／23（20.2秒）、完整Calendar
  actual-entry 62／62（94.6秒）。第一次official start因舊generation／ownership receipt不一致fail closed；
  `dev-status`證明ngrok為本worktree `ORPHAN_EXACT_RECONCILABLE`，受限stop verification未能證明停止後，改由
  official `dev-stop -SkipDispatcher`於70.5秒完整收斂。第二次official start 155.2秒PASS，generation=
  `66fde7659ea9404690987c1b2bf2a2d1`；ExternalLineProbe 82.6秒PASS，Spring UP／VERIFIED_DIRTY、DB／Redis
  healthy、LINE connected、Dispatcher DISARMED。現可真人LINE驗證；early deployment仍未滿足PR/release。
- Early deploy後的failure-path audit發現premature materialized parent若provider暫時 unavailable，舊setter只接受
  PENDING會再次卡死。新增recovery regression：先保存／retained而不產生完成claim，再查成功後仍修復同一plan。
  failure-first 1／1 error（170.6秒），修正後1／1 PASS（183.4秒）；完整Calendar actual-entry更新為63／63
  PASS（113.8秒）。因audit後曾在runtime運行時修改source，該generation立即作廢並由official dev-stop收斂；
  不沿用其runtime／LINE evidence，須再建立fresh generation。
- Fresh official start 183.7秒PASS，generation=`9cff4aa6dac24104b75fab6b042fa753`；ExternalLineProbe
  86.9秒PASS，Spring UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。
  此版已包含provider unavailable retain／retry recovery，可供本人真人LINE代表案例；尚未跑clean root、Automatic
  或新baseline，不能標READY。

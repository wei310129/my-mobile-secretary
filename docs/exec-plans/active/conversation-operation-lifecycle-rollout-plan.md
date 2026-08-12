# 全服務對話操作 Lifecycle 分批導入計畫

狀態：`ROUTE_ONLY_IMPLEMENTATION_ACTIVE`

## 目的與審查邊界

對話操作應具備一致的上層生命週期：開始、延續、補充資訊、關閉，以及清除未完成狀態後重新開始。這是共用介面能力，但不得一次套用所有功能。本計畫以「一次一個能力、一次一個可審查 PR」推進。

目前 production 實作範圍只包含路線規劃。除 route contributor 外，本輪不得新增其他 capability contributor，也不得藉 lifecycle 取得 Calendar、Booking、付款、取消、退款或外部 provider mutation 權限。

## 共用不變量

每一批能力都必須單獨證明：

1. 使用者可在任一步驟說「繼續」、「新的」或「全部清除重來」。
2. 清除只關閉目前 actor／workspace／channel／conversation scope 內尚未完成的 typed operation。
3. 已完成、已建立或已送往外部系統的資料不會被刪除；需要取消外部結果時必須走該領域原有的高風險授權流程。
4. 找不到唯一 typed owner 時 fail closed，明確告知目前不能安全清除，不得假裝成功。
5. replay exactly-once，不保存 LINE 原文或 generic JSON slot bag，不跨 actor／workspace／scope。
6. 公開訊息必須說出被延續或清除的操作名稱，並說明已完成資料是否保留；不得公開 UUID、schema、handler 或 internal reason。
7. 每一個 contributor 都要有 failure-first、focused、actual-entry、RLS、replay、exact mutation 與 zero unintended mutation 證據，並由使用者審查後才開始下一批。
8. 「繼續」必須重顯完整、可直接回答的目前問題與 typed next-question；不得只說「回答剛才的問題」。
9. 若造成上下文選擇的 turn 已是完整且驗證成功的新操作，選「新的」後必須直接消耗 capability-owned typed staged state，不得要求重複輸入。pending pointer 只保存 UUID，不保存原文、地址或 generic JSON；選擇前 provider 與 committed-resource mutation 均為 0。
10. 每個 contributor 的共用 resume contract 必須提供 typed `publicTopic`、`publicProgress` 與 `nextQuestion`；共用 renderer 固定依「目前正在處理 → 目前進度 → 下一步」輸出。主題只能來自 actor／workspace／workflow 綁定的 typed state，不得從最近原文、LLM 摘要或不唯一 history 猜測。
11. 找不到唯一可公開主題時 fail closed並提出一個操作選擇問題；不得顯示 UUID、schema、handler、raw address或其他內部識別資訊。

## 分批順序

### L0：路線規劃（本輪）

- typed owner：Calendar route intent draft。
- 支援 legacy pending identity 以 exact workflow UUID 回復 typed route identity。
- 清除 PENDING route draft、目前 pending question 與 conversation focus。
- MATERIALIZED route 只關閉對話上下文，既有 Calendar plan 保留。
- 不導入其他功能的 contributor。
- known five-step actual-entry 固定覆蓋：繼續 → 新完整路線 → 選新的 → 繼續 → 全部清除重來；至少三組常用控制語句。

### L0 最新實作證據（2026-08-10）

- 真人 LINE 根因：generic UNKNOWN 覆蓋 route question 後，舊實作只回「請接著回答剛才的問題」，沒有完整 prompt／typed next-question，使用者無法接續。
- failure-first：`ConversationContextTransitionServiceTest` 9 tests／2 failures，分別證明完整 buffer prompt 缺失及 generic UNKNOWN 後無法恢復。
- 第二個 failure-first：三組 known five-step actual-entry 均在「新的」後落到 `conversation.new-operation-content`，證明已解析的新路線內容被遺失。
- 修復：route contributor 可從 exact Calendar draft 重建完整問題；V115 只在 pending pointer 增加 nullable `deferred_workflow_id`。新完整 standalone route 先存 typed Calendar draft與 mode／time-role，選擇前不呼叫 provider、不建立 Calendar；選「新的」後在同一 transaction 啟用 typed draft並結束舊 pointer。
- focused unit 25／25 PASS（Maven 61.9 秒）；三組完整五步 actual-entry 3／3 PASS（正式批次 114.4 秒，後續同範圍重跑 63.2 秒）。actual-entry 驗證選擇前 provider count不增加，啟用後 exactly one新增呼叫，續接／清除不重查，Calendar plan始終為0。
- expanded lifecycle／RLS／actual-entry 73／73 PASS；capability catalog與clarification inventory精確重跑2／2 PASS。最後 production source的fresh clean root為2,148 tests、0 failure／error、18 skipped、Maven 718.4秒、正式exit 0。
- official runtime generation=`3e81c5ad1a644eceb9ca0516f7c76a50`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。Laptop schema v4 Automatic review於`2026-08-09T23:08:12.3285502Z`完成，六項capability MATCH、contract=`786e05082c5e226466c84d7c`、PASS/openCount=0，獨立assertion PASS。
- L0 production與環境驗證已完成；因本次production修正使舊baseline永久失效，仍須建立並完成全新24小時／20筆本人真實LINE文字baseline，L0不得標READY。

### L0 route start-reminder lifecycle follow-up（2026-08-10）

- 本輪仍只啟用 route contributor；共用 `CalendarStartReminderLifecycleService` 不代表其他 capability 已取得
  lifecycle 或 reminder mutation authority。
- 新 standalone route 在較高優先的衝突、相鄰缺地點與必要 connection-buffer 問題完成後才問一次出發提醒。
  relative reminder 隨 start revision 自動重排並告知；fixed-clock reminder 必須重新確認，回答後舊
  `REVIEW_REQUIRED` 規則在 exact plan/node/actor/workspace scope 內 exactly-once 取消。
- 後續 L1 依「建立／調整起始時間 → 檢查既有 reminder state → ASK／RETAINED_RELATIVE／REVIEW_FIXED」
  契約，逐一導入一般行程、活動與提醒 clarification；每一能力仍須獨立 failure-first、actual-entry與使用者審查。

### L0 context-first resume evidence（2026-08-10）

- 使用者拍板「繼續」時必須先重新說明目前主題，再說明進度，最後提供唯一下一步；這是共用上層介面，
  但本輪production仍只啟用route contributor。`ResumeQuestion`現強制typed `publicTopic`／`publicProgress`／
  question metadata完整；共用renderer固定依「目前正在處理 → 目前進度 → 下一步」輸出。
- topic只來自exact actor／workspace／workflow route draft title；generic UNKNOWN覆蓋、legacy root-domain與restart
  均由相同typed owner恢復，不讀LINE原文、不用LLM摘要、不公開internal identity。找不到唯一owner仍fail closed。
- expanded gate先發現typed question同時被renderer與`IntentResult.clarificationNeeded`附加而重複兩次；修正為renderer
  只輸出下一步標題，question由typed clarification boundary附加exactly once，完整75-test batch再跑通過。
- focused lifecycle 25／25 PASS（runner execution 79.6秒）；三組known five-step actual-entry 3／3 PASS（81.1秒）；
  expanded lifecycle／RLS／actual-entry 75／75 PASS（99.7秒）；capability catalog／clarification inventory 2／2
  PASS（12.4秒）。三組控制語句為「繼續／新的／全部清除重來」、「繼續處理／新操作／清除重來」、
  「延續／開始新的操作／全部取消重來」。provider與Calendar mutation assertion維持原exact counts。
- 最後production source fresh clean root 2,148 tests、18 skipped、0 failure／error，runner execution 767.8秒、
  正式exit 0。runtime generation=`59ceae2dc1cc4f62b4c01aea61b56819`，Spring Boot `UP/VERIFIED_DIRTY`、
  PostgreSQL／Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。
- Laptop schema v4 Automatic review reviewedAt=`2026-08-10T01:19:38.5120722Z`、contract
  `786e05082c5e226466c84d7c`，六項capability MATCH、PASS/openCount=0，獨立assertion PASS。所有先前runtime、
  Automatic與baseline evidence均因production修正失效；仍須全新24小時／20筆本人真實LINE文字baseline。

### L0 Route→Place child與共用狀態查詢（2026-08-11）

- 本批仍屬L0且只新增Route→Place一條edge；未替一般Calendar、Task、Travel或Booking啟用child contributor。
- failure-first 4／4失敗：三個常用狀態詢問均無法回答，未知route origin仍停在原slot且provider invocation為0。
- 修復後共用status interrupt可在不消耗pending的情況下重顯父主題、子階段、保留資訊、缺口與下一步。
  Place child支援「儲存並繼續／只用這次／取消建立地點」；plain取消只關閉leaf，明確取消整個行程與
  「全部清除重來」才關閉未完成tree。
- V116保存exact parent revision與typed provider candidate並FORCE RLS，不保存raw LINE text或generic JSON。
  provider不可用時父route保持PENDING並只問一題，Place／alias／Calendar mutation皆為0。
- expanded focused最新58／58 PASS（runner 67.8秒）；lifecycle／Place neighbors 60／60 PASS（14.2秒），
  RLS／migration／catalog／clarification inventory 14／14 PASS（44.2秒）。repo與installed skill官方驗證通過，
  normalized-newline 4／4 MATCH。runtime generation=`69c5a96fdab74d2a9490e1927adc7b27`，official
  `dev-start`及跨caller `dev-status -ExternalLineProbe`均確認LINE connected。尚待真人LINE代表案例與最後clean
  root，故L0不得標READY，其他L1–L5維持PLANNED。

### L0 colloquial status follow-up（2026-08-11）

- 真人LINE短句「處理到哪了」被漏接。根因是舊policy同時硬性要求`現在／目前`前綴與較長疑問詞，省略
  主詞／時間副詞的口語句因此落入一般意圖辨識。
- failure-first同一parameterized selector為4案中1案失敗；修復以direct progress semantic shapes，或
  current-context＋operation＋question組合判定，不新增reported full-sentence branch。8個常用status句與4個
  neighboring work句皆通過；完整context transition 30／30 PASS（33.1秒）。原句Route→Place actual-entry
  1／1 PASS（71.6秒），pending revision不變，Place／Calendar mutation皆為0。依使用者規則未跑root clean。

### L0 lifecycle context與共用選項契約（2026-08-12）

- 共用`ResumeQuestion`現在強制攜帶typed `LifecycleContext`：公開主題、目前父／子步驟、已保留事實與
  尚待確認事實；共用renderer固定依context-first順序輸出。泛用狀態問句只重顯同一份typed question，
  不消耗pending、不推進revision，provider與業務mutation皆為0。
- Route、Route→Place child與本輪Route附屬Reminder問題已接入；Place完成只回傳typed result給父Route，
  父流程仍有必要步驟時繼續，只有completion gate通過才可宣稱整個行程完成。其他capability未因介面
  存在而自動取得lifecycle或mutation authority。
- route conflict、provider unavailable、transport offer、activity adjustability、departure reminder及Place
  使用方式改由typed choice catalog與共用numbered renderer提供；公開顯示`1.`、`2.`、`3.`並由同一catalog
  解析數字／序數／標籤。direct-overlap另明說「本次行程尚未建立」。
- failure-first揭露多行choice被舊`ClarificationStep`拒絕後錯誤降級成再次詢問時間；改為typed
  `IntentResult.choiceNeeded`後，已提供的時間不再重問，actual-entry回覆`2`可exactly-once建立。
- focused 76／76 PASS（96.0秒）；Route／Place／Reminder鄰近163項首次158 PASS，5項均為舊公開契約斷言，
  更新後相關87項與最後單測皆PASS。catalog／RLS／registry 16／16 PASS，clarification inventory 1／1
  PASS。skill-creator官方`quick_validate.py`在UTF-8模式PASS。
- 第一輪fresh clean root 2,366項、18 skipped、2個舊文案契約failure（605.7秒）；補回direct-overlap
  未建立狀態並改驗typed choice後，兩項focused PASS。最終fresh clean root 2,366項、18 skipped、0 failure、
  runner execution 607.4秒。下一次同級root regression以607.4秒為基準，依功能／測試增減調整上限。
- Reminder多項清單已編號，但依fresh ordinal直接調整／刪除仍未取得本批mutation authority；明確列入
  `L1.2`獨立實作與審查，不把「顯示編號」誤稱為完整Reminder CRUD lifecycle。

### L1：一般行程／提醒 clarification

- 候選：建立／修改行程、schedule conflict、conditional recurrence、地點／時間補充、活動交通與提醒時間補充。
- 實作前先盤點哪些 draft 是可丟棄的，哪些已 materialize。
- 審查重點：清除草稿不得刪除既有 Calendar plan 或 Reminder。
- `L1.1` 一般 Calendar 建立／調整：逐一補 typed topic、active step、preserved／unresolved facts、每步 status與
  父子completion gate；不與 recurrence 或 reminder CRUD同批上線。
- `L1.2` Reminder 項次管理：多個提醒以共用 numbered renderer列出，使用者可用「1」「第一個」或公開標籤
  選取；調整／刪除前重新查詢fresh scoped list並以typed action確認，禁止保存stale ordinal或公開UUID。
  必測新增、查詢、調整、刪除、重播、並行變更、跨actor／workspace隔離及exact／zero unintended mutation。
- `L1.3` recurrence與conditional schedule：在前兩批safe gate審查後才啟用，不能藉共用lifecycle介面提前取得
  一般Calendar或Reminder mutation authority。

### L2：Task／Project／Travel 編輯模式

- 候選：task draft、一般／條件式／重複提醒、project mode、travel project focus。
- 實作前拍板「離開編輯模式」與「刪除資源」的公開語意；兩者不得合併成同一 mutation。
- 審查重點：關閉 conversation thread 不等於封存或刪除 Task／Project／Travel resource。

### L3：地點、語氣、repair 與其他本機對話草稿

- 候選：HOME／custom place／大型場站選點、route-operation preference、public place lookup、voice preference、conversation repair。
- 審查重點：只清除尚未完成的本機 draft；已確認的長期偏好不得被清除重來順帶刪除。

### L4：外部旅遊、票況與 Booking 對話

- 只有 matching Booking／Travel ownership handoff 後才開始。
- 清除本機對話永遠不等於取消 hold、訂單、付款、退款或 provider reservation。
- 任何外部 mutation 都需沿用原本 scoped authority 與再次確認，不由 lifecycle 擴權。
- 「繼續」只恢復本機對話上下文，永遠不等於 hold、booking、payment、cancellation或refund授權。

### L5：非同步與長時間工作

- 候選：背景分析、監測、批次整理。
- 實作前定義 cooperative cancellation、已產生成果的保留方式與通知語意。
- 審查重點：停止對話追蹤與停止執行中工作必須是兩個可辨識的 typed action。

## 每批出口

每批完成後先停在 safe gate，回報使用者可見訊息、資料保留／清除矩陣、測試數與未涵蓋能力。只有使用者審查通過，才把下一批狀態從 `PLANNED` 改為 `IMPLEMENTATION_ACTIVE`。

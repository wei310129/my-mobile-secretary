# 新行事曆／行程圖系統產品規格與開發測試計畫（Sol Medium 執行版）

> 跨 lane trigger：`parallel-development-trigger-registry.md`；Checkpoint B、Wheel 10、PR
> ready／merged 與 human-availability event 依 registry 執行，`HARD_YIELD` 不得略過。

> 狀態：**產品決策已確認；舵輪 0 待 Sol Medium 啟動**
>
> 更新日期：2026-07-23
>
> 執行者：GPT-5.6 Sol，推理模式 Medium
>
> 啟動提示詞：[calendar-plan-v2-sol-medium-prompt.md](calendar-plan-v2-sol-medium-prompt.md)
>
> D01–D61 已由使用者確認；本文件已登記為正式 active plan，durable 摘要已寫入
> `docs/decisions/current.md`。只有使用上述獨立提示詞才授權開始實作。

## 0. 文件用途與停止規則

這份文件先把產品心智模型、和市售行事曆的差異、資料與服務邊界、分階段 gate、擬真案例及測試標準
定義清楚，供使用者逐項確認。它不是開發授權，也不是 Sol Medium 的啟動提示詞。

Sol Medium 在未收到後續明確提示詞前不得：

- 修改舊 `ScheduleItem`、舊 `Reminder`、舊 API、舊 Intent 的既有語意。
- 建立 Flyway migration、程式碼、測試或 feature flag。
- 把本文件的「建議預設」當成已拍板決策。
- 修改正在進行的旅遊專案計畫以消除規格衝突。

任何標記為 `[P0 待確認]` 的項目未拍板時，對應階段必須停止；不得由 Sol 自選。

## 1. 目標、範圍與現況衝突

### 1.1 最終使用者成果

建立一套最終取代 legacy `schedule` 的新系統；開發與驗收期間暫時隔離並存，正式產品發布前完成
hard cutover 與 legacy retirement，使：

1. 行程是可觀看、可共享、可採用的「活動與路徑地圖」，不是一建立就把某個人鎖成忙碌。
2. 同一時間存在多個活動是合法資料；重疊本身不是建立失敗。
3. 一個行程計畫可有多個子活動；子活動不能再有下一層子活動。
4. 行程計畫與子活動可掛多個時間節點。
5. 「關鍵／一般」與「固定／有範圍／可調整」是兩個獨立維度。
6. 每個時間節點可有多筆提醒規則，支援節點當下與相對提前時間。
7. 每個人可同時加入多個群組日曆；群組活動的可見、關注、參加、節點採用與個人提醒彼此獨立。
8. 路徑可行性由 Java 依採用節點、地點、時間窗與交通證據計算；LLM 不計算衝突或自行移動資料。
9. 新系統具備一般行事曆的基礎能力，但不因相容市售格式而犧牲節點、採用與路徑語意。

### 1.2 Calendar core 與 cutover gate 前不做的事

- 不遷移、刪除、雙寫或改寫既有 `schedule_item` 資料。
- 不把 legacy 重疊規則改成新版規則。
- 不替使用者自動接受群組／共享節點、參加活動、移動可調節點或變更提醒。
- 不接付費航班、船班、鐵路或地圖 provider；需另取得同意。
- 不宣稱已改票、訂位、報到、付款、通知家人或聯絡外部單位。
- 不先做可任意巢狀的通用 Project framework。
- 不在此後端 repository 內假裝已交付原生 iPhone UI；目前未發現 Swift client source。

### 1.3 與現況的衝突

目前尚未切換的 runtime baseline 是：

- `ScheduleItem` 代表本人必須到場或上線，開始／結束區間會參與忙碌與撞期。
- `FeasibilityService` 將 `countsForActorBusy=true` 的已確認行程重疊視為問題。
- `Reminder` 是 Task 到點後的觸發／確認紀錄，不是任意時間節點的排程規則。
- 現有 travel/schedule runtime 仍以 `ScheduleItem` 運作，但 Travel 3B 已停止，不能再擴充 legacy
  ownership；恢復後改接 Calendar v2。
- `docs/architecture.md` 與 `docs/decisions/current.md` 已完成正式化更新；runtime 尚未實作 Calendar v2。

新版改為：

- 計畫或活動存在，不等於任何人採用或被占用。
- 只有某 actor 採用的節點才進入該 actor 的個人路徑投影。
- 記錄重疊永遠合法；若兩個已採用的物理約束無法同時達成，回傳風險／不可行評估，不刪除資料。
- 新提醒使用獨立規則與 occurrence，不冒充舊 Task reminder。
- ICS 只做一次性有損匯出與需確認的匯入 proposal；EventKit 不作後端 authoritative input。

開發期兩套語意必須以新 bounded context、資料表、API namespace 與 feature flag 隔離；最終產品只保留
新版 calendar source of truth。Legacy 退役是 release 後段的獨立 destructive gate，不得在核心尚未驗收時
提前刪除。

## 2. 建議產品詞彙與心智模型

以下名稱與細節以第 5 節決策狀態為準；未標示「已確認」者仍是建議。

| 使用者詞彙 | 程式概念 | 定義 |
| --- | --- | --- |
| 行程／行程計畫 | `CalendarPlan` | 根容器；可有標題、時間配置、地點、說明、附件與多個子活動 |
| 子行程／子活動 | `CalendarActivity` | 直屬一個 `CalendarPlan`；資料模型上沒有 activity-to-activity parent，因此不能有孫層 |
| 時間節點 | `CalendarTimeNode` | 掛在計畫或子活動上的時間錨點；可為絕對時間、相對邊界或相對另一節點 |
| 提醒 | `CalendarReminderRule` | 某 actor 對某節點的提醒規則；可有多筆，解析後形成可投遞 occurrence |
| 採用 | `CalendarAdoption` | 某 actor 明確選擇要納入自己規劃的活動／節點集合 |
| 群組日曆 | `GroupCalendar`（產品概念） | 一個人可加入多個群組；加入只取得該群組授權內容的可見性，不等同參加任何活動 |
| 參加狀態 | `CalendarParticipation` | 某 actor 對計畫、活動或 recurrence occurrence 的意向／承諾；不等同節點採用或提醒 |
| 共享 | `CalendarShare` | 對群組內或指定成員授予整份計畫／指定節點的權限，不等同參加或採用 |
| 路徑評估 | `PersonalRouteAssessment` | 只針對該 actor 已採用的節點計算可達性與風險 |

### 2.1 多群組與個人總覽

- `家庭` 只是一種群組類型／顯示名稱，不是 Calendar domain 的固定邊界；同一 actor 可加入多個群組。
- 第一版以一個 group workspace 對應一個群組日曆，保留未來一個群組擁有多個 calendar collection
  的擴充點，但不先實作多層 collection。
- 加入群組只代表可依 visibility policy 觀看活動；未明確參加／採用的活動不進個人路徑、
  availability 或 personal reminder。
- 「我的行事曆」是個人 workspace 與本人有權存取的各群組 workspace 的合併 read model；每筆結果
  必須標示來源群組與權限。
- 現行 RLS 每次只允許一個 workspace scope；跨群組總覽必須逐一驗證 membership、逐 workspace
  查詢後在 application read service 合併，不得放寬 RLS、使用 unrestricted repository 或把
  `SYSTEM` context 當一般查詢捷徑。
- 所有 mutation 必須指定唯一 workspace／group。LINE 指令若同名活動存在於多個群組，必須列出
  最小必要候選並回問，零 mutation。

### 2.2 根計畫與子活動

- `CalendarPlan` 可為時間區間、單一時點或全天。
- `CalendarActivity` 也可為時間區間、單一時點或全天。
- 子活動只屬於一個根計畫；不能再新增子活動。
- 計畫與子活動都可有地點；節點可覆寫成更精確的地點。
- 行程區間是日曆呈現與規劃背景，不自動成為 hard busy interval。

### 2.3 時間節點的獨立維度

每個節點至少有以下獨立屬性：

1. `criticality`
   - `CRITICAL`：錯過會造成明確損失、無法搭乘、無法入場或重大連鎖影響。
   - `NORMAL`：用於順序、提示或體驗改善；錯過不必然使整份計畫失效。
2. `adjustability`
   - `LOCKED`：一般拖曳／排程不能直接移動；變更必須建立明確 revision。
   - `WINDOWED`：可在最早／最晚時間範圍內安排。
   - `FLEXIBLE`：可提出調整建議，但系統仍不得未經同意自行 mutation。
3. `source`
   - `USER`、`EXTERNAL_EVIDENCE`、`DERIVED`。
4. `timeExpression`
   - 絕對時間。
   - 相對計畫／活動開始或結束。
   - 相對另一個節點的 offset。

`CRITICAL + FLEXIBLE`、`NORMAL + LOCKED` 都是合法組合，不能用單一布林欄位混在一起。

`LOCKED` 不代表世界上永遠不會變；它代表不能被一般排程動作直接覆寫。外部班次變更或使用者明確修正
必須走 revision／review 流程並留下來源與版本。

### 2.4 使用者範例的唯一計算結果

輸入：

- 根計畫：週六 11:00–16:00「去裕隆城逛逛」。
- 子活動：11:30「關鍵活動」。
- 關鍵節點：「排隊」，相對子活動開始 `-10 分鐘`。
- 提醒 A：節點當下。
- 提醒 B：相對節點 `-15 分鐘`。

Java 必須解析為：

- 排隊節點：11:20。
- 提醒 A：11:20。
- 提醒 B：11:05。

移動子活動時，相對節點與相對提醒一起重算；若提醒被明確設為「固定絕對時間」，則不跟著移動，
並要顯示可能不再合理的警告。相同 inbound 重送不得增加第二份子活動、節點或提醒。

### 2.5 重疊與路徑可行性

建立資料與評估可行性必須分離：

- 任意計畫／活動可重疊並同時顯示。
- 未採用的共享節點不限制個人路徑。
- 已採用但純資訊性的節點只顯示，不產生 hard conflict。
- 已採用的時間／地點約束若物理上不可同時達成，資料仍保存，但評估回傳 `IMPOSSIBLE`。
- 自動規劃器不得把 `IMPOSSIBLE` 說成可行，也不得自行刪除其中一個選項。
- 手動採用不可行組合時，先明確警告並要求使用者決定要保留風險、取消採用或調整 flexible node。

## 3. 與市售行事曆的使用差異

官方產品基線（查閱日期 2026-07-23）：

- Apple Calendar 的 event 以標題、地點／視訊、開始／結束、travel time、受邀者、附件與 alert
  為核心；加入地址後可用 Apple Maps 提供出發提示：
  <https://support.apple.com/guide/iphone/-iph3d110f84/ios>
- iCloud Calendar 可為 event 設第一與第二個 alert：
  <https://support.apple.com/guide/icloud/set-an-alert-mmbe94aba4/icloud>
- iPhone 可建立多個 calendar；iCloud shared calendar 主要是 calendar 級的可檢視／可編輯權限：
  <https://support.apple.com/en-ph/guide/iphone/-iph3d1110d4/ios>
  與 <https://support.apple.com/guide/iphone/iph7613c4fb/ios>
- Apple event invitation 提供接受、可能、拒絕與提議新時間：
  <https://support.apple.com/guide/iphone/reply-to-invitations-iphc0eddfe3c/26/ios/26>
- Apple Calendar 可按標題、受邀者、地點與 notes 搜尋 event：
  <https://support.apple.com/en-ie/guide/iphone/iph2c9ef44ad/ios>
- Apple event 可保存實體地點／視訊連結與附件；Calendar color 則主要設定在 calendar layer，而非
  每筆 event：
  <https://support.apple.com/en-lamr/guide/iphone/-iph3d110f84/ios>
  與 <https://support.apple.com/id-id/guide/iphone/iph3d1110d4/ios>
- Apple Calendar 的自訂重複事件支援每日、每週多星期、每月日期／pattern、每年與結束條件，並提供
  只刪本次或本次及未來事件：
  <https://support.apple.com/en-lamr/guide/calendar/icl1018/mac>
- EventKit 是 Apple 裝置端 framework，必須由 app 建立 `EKEventStore` 並取得使用者授權；iOS 17+
  可請求 write-only 或 full access。因此 Backend／LINE 不能假裝直接完成裝置同步，原生 client
  應採完成目的所需的最小權限：
  <https://developer.apple.com/documentation/EventKit/accessing-the-event-store>
- RFC 5545 recurrence set 由 `DTSTART + RRULE + RDATE - EXDATE` 構成；`UNTIL` 為 inclusive，
  無效日期或不存在的 local time 必須略過且不計入 occurrence count：
  <https://datatracker.ietf.org/doc/html/rfc5545>
- Google Calendar 的可用時段仍以 event 的 `Busy` 狀態排除預約時間：
  <https://support.google.com/calendar/answer/16287054>

| 面向 | iPhone／一般 event-centric calendar | 本系統建議 |
| --- | --- | --- |
| 核心單位 | 一筆 event 通常是一段開始／結束時間 | 一份計畫可有一層子活動與多個時間節點 |
| 人與行程 | event／RSVP 常直接影響 busy／availability | 計畫存在與個人採用分離 |
| 重疊 | 可顯示重疊，但 availability 通常以 busy block 解讀 | 重疊是正常資料；只評估已採用約束的物理可達性 |
| 時間語意 | event start/end 為主 | 範圍／時點之外，節點另有關鍵性、調整性、來源與 offset |
| 提醒 | event alert；iCloud 官方 UI 明載第一／第二 alert | 每個節點可有多筆個人提醒與共享建議模板 |
| 共享 | 分享整個 calendar 或邀請 event、RSVP | 可分享整份計畫或指定節點；分享、採用、個人提醒互相獨立 |
| 多群組參加 | 加入 calendar／受邀 event 後以 RSVP 回覆為主 | 可同時看多個群組活動；membership、關注、參加、節點採用與提醒分離 |
| 報名營運 | 以受邀者接受／可能／拒絕等 event 回覆為主 | 活動可另管截止、具名 roster、名額、候補、超額、逾期加入／退出與負責人通知 |
| 重複事件 | 每日／每週／每月／每年與本次／未來系列操作 | 系列可含多節點、series/occurrence adoption、單次略過、每場或整系列名額與 versioned review |
| 變更 | 編輯 event 後同步給參與者 | 關鍵 shared node 版本化，採用者需 review，不靜默改個人路徑 |
| 交通 | event travel time／time-to-leave | 多節點形成路徑限制，可表達登機、集合、上／下船、轉乘等不同錨點 |
| 外部格式 | EventKit／ICS 能表達一般 event、recurrence 與 alert，但沒有本系統完整的採用、節點 revision、名額／候補與權威變更語意 | 匯出是明示有損的個人 projection；匯入先成為 proposal，節點、採用與 revision 仍以後端為 source of truth |

### 3.1 建議保留的一般行事曆基礎

分階段提供：

- 日／週／月／列表查詢所需的 query model。
- 時間區間、單一時點、全天、跨日與時區。
- 標題、說明、地點／線上連結、顏色／分類、附件 reference。
- 搜尋、篩選、複製、取消、封存。
- 基本 recurrence 與 occurrence exception。
- 多個日曆 layer／來源顯示。
- 共享、權限、採用狀態與變更通知。
- EventKit／ICS adapter 只做 anti-corruption projection／import proposal，不進入核心 aggregate；
  第一版具體範圍依 D49–D56 決定。

`[已確認 2026-07-23]` 第一個 backend release 不含外部 email guest invitation、外部 RSVP 回寫、
退信處理或外部身分對應；會議室預訂與原生 iPhone UI 亦不在本 release。系統內群組成員的
participation／registration 不受此限制。

## 4. 建議使用流程

### 4.1 建立一般個人活動

1. 使用者建立「週六 11–16 裕隆城」。
2. 系統建立新 `CalendarPlan`，不查 legacy overlap 來阻擋。
3. 建立者可選擇是否立即採用；不得因「建立」或群組 membership 默認任何成員參加／採用。
4. 若缺必要日期、時區或時間語意，先問清楚，零 mutation。

### 4.2 增加子活動與節點

1. 在計畫內新增 11:30 子活動。
2. 增加相對開始 `-10m` 的排隊節點。
3. 標記 criticality 與 adjustability。
4. 增加節點當下與 `-15m` 兩筆提醒。
5. 回覆先列出最終解析時間，再說可調整選項。

### 4.3 參加並採用群組／共享計畫

1. 成員在某個群組日曆看到有權觀看的計畫或活動；此時尚未自動參加。
2. 本人選擇參加狀態；`WATCHING` 應改建模為關注／通知訂閱，不再與參加承諾混為同一 enum。
3. 本人選擇採用全部既有節點或明確節點集合，形成 versioned adoption snapshot。
4. 個人提醒由本人確認；共享提醒只能作 template，不可直接對所有人排程。
5. 新增的未來節點預設不自動加入既有採用 snapshot，先送 review。

### 4.4 略過／退出活動與相關訊息

已確認的產品方向是：本人可在系統揭露影響並正式確認後，略過原本可見、關注或已參加的特定
行程；群組管理者不能把本人永久綁死在活動上。

以下具體流程已由使用者於 2026-07-23 確認：

1. v1 可對整個 plan、單一 activity 或單次 recurrence occurrence 執行略過；node 的增減仍走
   adoption snapshot，避免「略過節點」與「仍參加活動」產生不明確狀態。
2. 執行前由 Java 建立 impact preview，列出參加政策、criticality、受影響節點、個人路徑、
   future reminders、一般活動訊息、相依項目與目前 revision；不能由 LLM 自行推算。
3. `OPTIONAL` 且從未參加／採用的活動可直接忽略，預設本來就不送 activity-specific 訊息；
   已 `TENTATIVE／COMMITTED`、`REQUIRED`、critical 或有相依項目者，必須使用較強警示與
   explicit confirmation token。
4. 確認成功後，只變更本人 participation／adoption／notification projection：移出個人 route 與
   availability、取消該 scope 尚未觸發的 actor-owned reminders、停止一般活動更新；群組原始
   plan、其他成員資料與歷史稽核不得被刪除。
5. linked Task／Knowledge 不自動刪除或完成；preview 必須說明仍保留哪些項目，若要取消 Task
   必須另走 Task command。
6. 群組安全／權限／membership、緊急廣播、略過交易結果與投遞失敗等 non-suppressible 系統訊息
   不得被「略過相關訊息」一併靜音。本人完成略過後，不再收到該活動的一般 reminder／routine
   update。
7. revision 已變更、scope 不明或同名活動跨群組時，confirmation 失效並重新 preview；重送相同
   confirmation 必須冪等。

### 4.5 共享關鍵節點被修改

一般共享變更流程：

1. 建立新 node revision，不覆蓋稽核歷史。
2. 將採用舊 revision 的個人投影標記 `REVIEW_REQUIRED`。
3. 移除尚未觸發的舊時間 occurrence，立即通知「時間已變更，請確認」。
4. 使用者接受後才依新 revision 建立個人提醒。
5. 不把「資料來源已變更」誤寫成「票券已改好」。

使用者已於 2026-07-23 確認一般流程，並確認以下「權威變更」例外：

- `CRITICAL` 不自動代表可強制修改；節點另有 `REVIEW_REQUIRED` 或
  `AUTHORIZED_AUTO_APPLY` 變更政策。
- 只有取得特定計畫或節點 scope 的 `AUTHORITATIVE_EDITOR` 能提交權威變更，且必須記錄原因、
  來源、變更前後值與 revision。
- 權威變更只涵蓋時間／時間窗、執行地點，以及取消／恢復等客觀狀態；標題、備註與附件不能
  藉此強制改變個人路徑。
- `COMMITTED` 採用者自動套用新 revision；`TENTATIVE` 進 review；`WATCHING` 只依私人 subscription
  接收 routine update，不產生 constraint；`DECLINED／OPTED_OUT` 不產生 constraint 或提醒。
  使用者仍可退出行程，但不能把已失效的舊時間當成有效事實。
- 自動套用後標記為 awaiting acknowledgement；不得記成使用者本人已同意或已讀。
- 變更與高優先通知必須在同一可靠 outbox transaction 建立。舊 occurrence 取消；既有個人相對
  reminder rule 保留 offset 並依新 revision 重算；絕對時間 rule 停止舊 occurrence 並進 review。
  權威編輯者不能替別人新增或改變私人提醒頻率。
- 新 revision 立即觸發個人路徑重算；不可達時保留資料並警告，不自動刪除其他採用。
- 投遞失敗必須重試並顯示失敗，不能宣稱已通知；相同 revision 重送不得產生重複變更或通知。
- 誤改只能以後續 revision 修正，不覆寫稽核歷史。

### 4.6 活動報名、名額與候補管理

已確認的產品方向是：活動負責單位必須能查看其 scope 內的具名參加者名單，且活動 owner 可設定
報名截止後有人加入或退出時，通知指定負責人並提供名額、候補與退出紀錄管理。

以下完整流程已由使用者於 2026-07-23 確認：

1. activity／recurrence occurrence 可設定報名開始、報名截止、名額上限、候補是否啟用、超額模式、
   截止後加入政策、候補升補模式與逾期異動通知。未設定名額上限代表不限制，不得用任意大數字代替。
2. 活動 owner 可指派一或多名 scoped `PARTICIPANT_MANAGER`；負責單位可作為顯示名稱，但實際
   授權必須落到可稽核的 actor assignment。一般 group admin 不因職稱自動取得所有活動的完整名單。
3. manager roster 至少顯示：參加者顯示名稱、`REQUESTED／WAITLISTED／TENTATIVE／COMMITTED／
   WITHDRAWN_BY_USER／REMOVED_BY_ORGANIZER／DECLINED`、狀態時間、候補順位與異動來源；不得顯示
   personal reminder、私人 Knowledge、未共享位置或其他群組資料。
   即使活動開啟參加者互相可見，也只公開 active `TENTATIVE／COMMITTED` 名單；候補、退出、
   被移除與拒絕名單仍只限授權 manager。候補者只看自己的順位與彙總人數。
4. 名額只由 `COMMITTED` 占用；`TENTATIVE` 不保留名額，對使用者必須明說。到達上限時依 policy
   拒絕、進候補或建立待審核 request，不得靠競態偶然超賣。
5. 截止後加入政策建議為 `CLOSED／WAITLIST_ONLY／REQUIRE_APPROVAL／ALLOW_IF_CAPACITY`；符合
   owner 設定後才能進入對應狀態，不能把 request／waitlist 說成報名成功。
6. 本人退出永遠允許；截止後或 critical／REQUIRED 活動先顯示影響並正式確認，但 manager 只能
   管理空缺與後續安排，不能否決退出。狀態必須記為 `WITHDRAWN_BY_USER`；退出原因由本人自願
   提供，不能作為退出必填欄位，只有本人明確分享時 manager 才可見。
7. manager 可移除參加者，但必須記為 `REMOVED_BY_ORGANIZER`、保存原因與操作者，並可靠通知
   被移除者；不得冒充本人退出或本人已讀。manager 也只能邀請／核准，不得替別人偽造參加同意。
8. 候補預設依進入時間 FIFO；若 manager 手動調整順位，必須有原因與 audit。釋出名額時可選
   `MANUAL_OFFER` 或 `AUTO_OFFER`，兩者都只送出有期限的候補邀請；候補者明確接受後才
   `COMMITTED`，逾期由注入的 `Clock` 推進下一位。
9. 名額模式建議為 `HARD_LIMIT／MANAGER_OVERRIDE`。後者只有 scoped manager 能在警示、輸入原因
   並確認後超額；下修名額若低於已確認人數，只標記 `OVER_CAPACITY` 並通知 manager，不得自動移除
   任何人。
10. 逾期異動通知 v1 建議支援 `OFF／IMMEDIATE`，活動啟用名額或候補時預設 `IMMEDIATE`。通知只送
    owner 指定的 registration recipients，內容包含活動、加入／退出者顯示名稱、異動時間、異動前後
    committed／waitlisted／remaining／over-capacity 數量與候補處理結果；以 outbox 保證可重試與冪等。
11. 報名成功圖片／文件仍是 Knowledge／evidence；若要對群組活動建立或更新 registration，必須先
    精確匹配 activity、驗證 actor／workspace 與明確 materialization consent，不能僅憑 OCR 文字改動
    群組名單。
12. v1 管理範圍先包含 roster、名額、候補、加入／退出與 audit；報到 QR、簽到、no-show、票券付款
    與退款另列後續能力，不混進 Calendar core。

### 4.7 重複活動與 occurrence

以下 recurrence 規則已由使用者於 2026-07-23 確認：

1. v1 pattern 支援 `DAILY／WEEKLY／MONTHLY／YEARLY` 與正整數 interval；weekly 可選多個 weekday；
   monthly/yearly 可用日期、第 N／最後一個 weekday、最後一天。`HOURLY／MINUTELY／SECONDLY`
   不屬於 Calendar v1。
2. 結束條件支援永不結束、`COUNT` 或 inclusive `UNTIL`；另支援排除日期、追加單次 occurrence 與
   單次 override。無限系列只保存 rule，不預先建立無限資料。
3. edit／cancel scope 支援 `THIS_OCCURRENCE／THIS_AND_FUTURE／ENTIRE_SERIES`。本次及未來以 split
   series 建立新 revision；過去 occurrence 與 audit 永不被回寫。`ENTIRE_SERIES` 對已發生部分也只保留
   歷史，實際修改未來 projection。
4. recurrence rule 可掛在 plan 或 activity，但 ancestry 最多一個 recurrence owner：plan 已重複時，
   child activity 不得再有自己的 rule；非重複 plan 內可有 recurring activity。owner 下的 node、
   reminder template 與相對時間依每個 occurrence 解析。
5. participation／adoption 可選單次或整系列。本人明確採用 series 表示同意同一 rule revision
   產生的未來 occurrence，自動納入 projection；新增 node template 或修改 rule revision 仍依 D06
   進 review，不能藉 series consent 自動加入新義務。略過單次只建立 occurrence exception，不退出系列。
6. registration scope 由 owner 選 `SERIES／EACH_OCCURRENCE`，預設 `EACH_OCCURRENCE`：
   - `SERIES`：一份 registration 與一個席位涵蓋整系列；略過單次不釋出系列席位，退出系列才釋出。
   - `EACH_OCCURRENCE`：每場各自計名額、截止、候補與 roster；略過該場會釋出該場席位。
   已有 registration 後不得靜默切換 scope，必須 impact preview、revision 與受影響者 review。
7. timed recurrence 以 `LocalDateTime + ZoneId` 保存 wall-clock anchor，再逐 occurrence 解析 Instant；
   all-day 使用 `LocalDate`。月末不存在日期、2/29 非閏年或 DST gap occurrence 依 RFC 語意略過，
   不自動夾到月底或移到其他時間；要「每月最後一天」必須使用明確 pattern。DST overlap 使用第一個
   valid offset。建立／修改時要預覽被略過日期；critical／locked series 有 invalid occurrence 時必須
   額外確認。
8. recurrence rule、exception、override、split lineage 與 occurrence identity 由 typed value object／
   domain service 決定，不把 raw RRULE 當核心 source of truth。ICS adapter 後續做雙向有損映射；
   unsupported rule 只能建立 import proposal。
9. reminder quota 仍計在 actor/node template，不按 occurrence 重複計 8 筆。query 只展開 caller 指定
   且有上限的時間窗；reminder worker 只維持有限 rolling materialization，依 fire time 補充下一批，
   不建立無限 occurrence。exception／split／replay 必須取消與重建正確 fire time 且零重複投遞。

### 4.8 分享範圍、內容與權限繼承

以下 sharing 規則已由使用者於 2026-07-23 確認：

1. 第一版 visibility 支援 `PRIVATE／SELECTED_MEMBERS／GROUP_VISIBLE`。shared target 必須是 owning
   workspace 的 active member；資料仍只屬於一個 workspace，不複製到另一群組。要分享給其他群組
   的人，對方需先加入 owning group。D39 已排除 public link、外部 email guest 與跨系統 RSVP。
2. share scope 支援：
   - `LIVE_WHOLE_PLAN`：分享整份 plan，既有與未來新增 activity/node 都可見；但新 node 是否進個人
     route 仍受 D06 adoption review 約束。
   - `SELECTED_ACTIVITIES`：只分享選定 activity 與當下選定／既有 node snapshot；後續新增 node 不
     自動分享，owner 必須 preview 後擴充 scope。
   - `SELECTED_NODES`：只分享選定 node 與最小必要 plan/activity context，不曝光其他 children。
3. ACL 採 additive grant，不做 deny override。plan grant 向下適用；activity／node grant 只擴大該
   scope，不能縮小既有較高層 grant。撤銷一筆 grant 後，其他獨立 grant 仍有效，effective permission
   必須由 Java deterministic 計算並可解釋來源。
4. v1 權限／能力分開：
   - `VIEWER`：讀取已分享 core fields。
   - `EDITOR`：在 scope 內建立一般 revision，但不能管理 share、替別人參加／採用、改私人提醒、
     看 roster 或做權威強制變更。
   - `AUTHORITATIVE_EDITOR`：只依 D14 scoped objective fields。
   - `PARTICIPANT_MANAGER`：只依 D25–D31 管 registration／roster。
   - share grant/revoke 與 ownership transfer 第一版只允許 plan owner；一般 group admin 不自動取得
     private plan 的 share manager 權限。
5. selected node 必須揭露可理解但最小的 context：plan/activity title、owner/organizer display name、
   resolved time/window、location、criticality／adjustability 與變更狀態。若 relative node 依賴未分享
   base node，系統需在 preview 列出一層 dependency closure 並只分享重算所需的 minimal projection；
   base 不允許揭露時拒絕分享，不能傳一個日後無法驗證的孤立時間。
6. core share 不自動包含附件、私人 notes、Knowledge、tag graph、個人 route、availability、位置事件、
   reminder rule/channel 或 roster。附件逐 asset 明確授權；Knowledge 只走已確認的 versioned excerpt；
   roster 只依 participant-manager／participant-visibility policy。
7. share grant/revoke/role change 必須 versioned、audit、idempotent 並透過 outbox 各通知 recipient 一次；
   routine content update 仍依本人的 watch preference，不因 VIEWER 自動訂閱所有訊息。
8. 撤銷 share 不得刪除 recipient 已明確建立的 personal adoption snapshot、route constraint 或 reminder；
   保留最後接受的最小資料並標示「來源存取已撤銷／不再更新」，附件與 Knowledge excerpt 則立即失效。
   recipient 可自行移除 personal copy。
9. owner 不得對仍為 `TENTATIVE／COMMITTED` 的 participant 直接撤銷最後一條必要存取路徑。必須選擇：
   - 保留 participant-minimum access；或
   - 走 `REMOVED_BY_ORGANIZER`、影響 preview、取消未來 participant projection 並可靠通知。
   membership removal 亦適用同一規則，不得用退群繞過 participant lifecycle。
10. ownership transfer 只允許同 workspace active member，採 `OFFERED／ACCEPTED` 兩階段；target 接受前
    原 owner 仍負責，不能把責任單方面丟給別人。transfer 不改 participation、adoption、private reminder
    或 registration，且 owner 有 active plan 時離開群組前必須 transfer 或 archive。

### 4.9 EventKit 與 ICS 外部行事曆邊界

以下 D12／D49–D56 規則已由使用者於 2026-07-23 確認：

1. EventKit 延後到原生 iOS client 專案。未來預設使用 EventKitUI 或 write-only access 完成「加入
   Apple 行事曆」；只有另案核准真正的雙向讀取／比對需求，才請求 full access。裝置日曆永遠不是
   後端 Calendar 的 source of truth。
2. 第一個 Backend REST＋LINE release 交付：
   - 經授權的一次性 ICS snapshot 匯出；
   - ICS 檔案匯入預覽與 proposal；
   - 不交付公開／訂閱型 webcal feed、背景自動刷新、雙向同步或外部變更自動覆寫。
3. 匯出只允許本人擁有或已明確採用的 personal projection，可選單一 plan 或最長 366 天的日期範圍。
   預設 `COMPACT`：plan/activity 各形成可理解的 `VEVENT`，關鍵 node 摘要在 description；使用者可另選
   `ROUTE_AWARE`，把本人已採用的 critical／route node 額外投影成獨立 `VEVENT`。未採用 node 不因
   可見或分享而被匯出成個人路徑。
4. 匯出不得包含 roster、其他 actor 身分／狀態、私人 Knowledge／tag graph、未獨立授權附件、位置
   歷史或別人的 reminder。availability 可有損映射為 `TRANSP`；只有 actor-owned active reminder
   可在可表達時映射為 `VALARM`。ACK、retry／escalation、shared template、權威變更與 adoption review
   無法保真，下載前必須顯示 loss report。匯出的檔案一旦交付便無法撤回，介面必須先告知。
5. REST 使用已驗證 actor session 下載；LINE 若不能直接交付檔案，只能產生短效、單次使用的下載
   artifact，不建立永久公開 URL。產檔時重新驗證 scope；過期、已使用、撤銷授權或重送 token 都
   fail closed。
6. 匯入檔案一律視為不可信、actor-private evidence。Java parser 先產生逐項 preview／proposal；
   使用者明確勾選欄位與確認前，CalendarPlan、Task、reminder、participation、registration、share
   與 Knowledge fact mutation 全部為零。
7. `METHOD:REQUEST／CANCEL`、`ORGANIZER`、`ATTENDEE` 與 `VALARM` 都只是輸入資料：
   - scheduling method 不執行邀請、取消或覆寫；
   - organizer／attendee 只在私人預覽顯示，不建立內部 actor、群組 membership、roster 或 RSVP；
   - `VALARM` 只轉成待確認的個人 reminder 建議，仍受每 node 8 筆上限與 offset 規則；
   - `URL／ATTACH` 不觸發網路抓取、登入、下載、開啟或 Knowledge materialization。
8. 外部 `UID + RECURRENCE-ID + SEQUENCE` 只在
   `workspace + actor + import-source` scope 內協助辨認 replay／可能 revision，不能當權威。相同檔案
   或確認請求重送只能 materialize 一次；較高 `SEQUENCE` 只能提出 revision proposal，不能自動改
   已存在的計畫。
9. 可完整映射的 RRULE 才轉成 D32–D38 typed recurrence；unsupported rule 留在 proposal 並要求調整，
   v1 不擅自展平為大量 one-off events。帶 `TZID` 的時間須通過 `ZoneId`／`VTIMEZONE` 驗證；floating
   time 在使用者選定 `ZoneId` 前不得 materialize。
10. 預設安全限制為每檔 5 MiB、10,000 個 `VEVENT`；匯出日期窗最多 366 天且產物有短效期限。限制
    必須可由設定調低、錯誤使用者可理解，解析採 streaming／bounded expansion；不得以 LLM 解析 ICS，
    也不得追蹤任一外部 URI。

### 4.10 搜尋、線上連結、附件與顏色

以下 D57–D61 規則已由使用者於 2026-07-23 確認：

1. 第一版產品優先級為：
   - release gate：安全搜尋與 typed 線上會議連結；
   - cutover 前：利用既有 `StoredMedia` 完成附件 binding／授權；
   - iOS UI 階段：個人 layer color。
   搜尋是多群組與自然語言修改能安全定位 target 的必要能力；顏色在 Backend REST＋LINE 階段
   幾乎沒有核心價值，不應為它提前建立共享商業語意。
2. 搜尋 v1 支援 title、plan/activity/node label、shared description、location、category 與 online-link
   label/host，以及 time range、workspace/group、lifecycle、participation/adoption、criticality filter。
   「我的行事曆」仍逐 workspace 授權查詢後合併、排序、分頁；不得以 global/SYSTEM query 繞過 RLS。
3. 搜尋不索引 roster／attendee、退出原因、私人 reminder／route／location history、raw attachment、
   私人 Knowledge 或未授權 excerpt。Knowledge 搜尋仍走自己的授權與 typed binding；自然語言只
   產生 typed search criteria，實際查詢、排序、上限與 target disambiguation 由 Java 決定。
4. v1 每個 plan/activity/node 最多一個 primary online access link，僅允許 normalized `https` URI 與
   可選 label；系統不抓 preview、不跟隨 redirect、不驗證會議是否存在、不自動加入會議。link 隨
   target 的 core visibility 分享；selected scope 只帶達成該 target 所需的 link，完整 URI 不進 log、
   LifeRecord、search snippet 或錯誤訊息。
5. 附件不把 binary 放入 Calendar table，也不重新做 media storage。使用 typed
   `CalendarAttachmentBinding` 指向既有 actor-private `StoredMedia`，沿用既有 MIME signature、
   15 MiB 單檔上限與 actor quota；第一版不增加新格式或遠端 URL attachment。
6. 把附件綁到 shared plan 不等於分享 bytes。必須依 D45 對每個 asset、指定 share scope／recipient
   明確授權；撤銷 content grant 後立即不可下載，但不刪 owner 原檔。解除 binding 與永久刪除 media
   是兩個操作；replacement 建立新 binding revision，不覆寫舊檔與 audit。下載使用安全
   content-disposition／MIME header，Office／PDF 不在 server 解析或執行。
7. `category` 是可分享、可搜尋的語意欄位，第一版每個 plan/activity 可有一個 normalized label；
   顏色不是狀態、權限或 category。第一版不交付 color mutation；iOS 階段若加入，採 actor-private
   group/calendar-layer preference，owner 不能替所有人強制顏色，且 UI 不得只靠顏色表達 critical、
   participation 或 conflict。

## 5. 核心產品決策清單

以下以狀態欄為準；「待確認」項目不得先行實作：

| ID | 建議 | 狀態 |
| --- | --- | --- |
| D01 | 新 bounded context 使用 `calendar`；legacy `schedule` 只在開發／驗收期暫留 | 已確認（2026-07-23） |
| D02 | 根 `CalendarPlan` + 一層 `CalendarActivity`，不做遞迴 parent | 已確認（2026-07-23） |
| D03 | 任意重疊可建立；不可行只影響 route assessment／自動規劃承諾 | 已確認（2026-07-23） |
| D04 | criticality 與 adjustability 分離；adjustability 採三態 | 已確認（2026-07-23） |
| D05 | 建立、共享、採用、busy impact、提醒是五件不同操作 | 已確認（2026-07-23） |
| D06 | 採用是 versioned snapshot；後續新節點不自動採用 | 已確認（2026-07-23） |
| D07 | shared reminder 只提供建議 template；本人確認後才建立 actor-owned reminder | 已確認（2026-07-23） |
| D08 | 一般日曆 alert 預設一次；CRITICAL 建立提醒時必須詢問是否啟用 ACK，且間隔／次數須明示，不自動沿用 legacy Task 預設 | 已確認（2026-07-23） |
| D09 | 不遷移測試資料；新版驗收後 hard cutover，正式產品發布前完整退役 legacy schedule，且實際刪除需獨立 destructive gate | 已確認（2026-07-23） |
| D10 | timed data 保存 `Instant + ZoneId`；全天保存 `LocalDate`，不用假 00:00 | 已確認（2026-07-23） |
| D11 | relative node dependency v1 最多一層，避免任意時間圖 cycle | 已確認（2026-07-23） |
| D12 | EventKit／ICS 為有損 adapter，後端新系統仍是 source of truth | 已確認（2026-07-23） |
| D13 | recurrence 在核心 one-off flow 穩定後依 D32–D38 交付 | 已確認（2026-07-23） |
| D14 | 一般共享變更需 review；具 scoped authority 的客觀權威變更對 COMMITTED 採用者自動套用並可靠通知，但不得偽造本人同意／已讀 | 已確認（2026-07-23） |
| D15 | 第一版交付 Backend REST API v2 + LINE；iOS UI 另案處理 | 已確認（2026-07-23） |
| D16 | 開發期以 feature flag／指定 actor pilot；驗收後 hard cutover、短期觀察，再進 legacy retirement gate | 已確認（2026-07-23） |
| D17 | Draft、Task、Calendar、Knowledge 保持各自 source of truth，以 typed binding 整合；舊 SCHEDULE_REMINDER 退役，Reminder 改為附屬規則 | 已確認（2026-07-23） |
| D18 | 每位 actor／每個 node 最多 8 個 active personal reminder rules；每個 node 最多 8 個 active shared templates | 已確認（2026-07-23） |
| D19 | Calendar v1 reminder 只允許節點當下或之前；事後需追蹤的行為建立 Task | 已確認（2026-07-23） |
| D20 | `家庭` 泛化為多群組日曆；同一 actor 可加入多個群組，membership／visibility 不等同參加、節點採用或提醒 | 已確認（2026-07-23） |
| D21 | 參加政策與 criticality／adjustability 分離，採 `OPTIONAL／RECOMMENDED／REQUIRED`；即使 REQUIRED 仍允許本人經較強警示後明確退出 | 已確認（2026-07-23） |
| D22 | 略過 scope 為 plan／activity／單次 occurrence，只改本人 projection；依 impact preview＋revision-bound confirmation 取消 constraints／future reminders 並靜音一般活動訊息，不刪 shared source、Task、Knowledge、歷史或他人資料 | 已確認（2026-07-23） |
| D23 | 「我的行事曆」以逐 workspace 授權查詢合併多群組 read model；mutation 必須指定唯一群組，不得繞過 RLS | 已確認（2026-07-23） |
| D24 | `WATCHING` 永遠私人；`TENTATIVE／COMMITTED／OPTED_OUT` 預設只讓 owner／organizer 看，活動可另開參與者互相可見 | 已確認（2026-07-23） |
| D25 | 活動 owner 與 scoped participant manager 可查看具名 roster；一般成員與未授權 group admin 不可查看完整名單或私人資料 | 已確認（2026-07-23） |
| D26 | owner 可設定報名截止後加入／退出通知，由指定負責人管理名額上限、候補順位、超額狀態與退出者紀錄 | 已確認（2026-07-23） |
| D27 | 截止後加入採 `CLOSED／WAITLIST_ONLY／REQUIRE_APPROVAL／ALLOW_IF_CAPACITY`；本人退出永遠允許，manager removal 與 user withdrawal 必須分開且可靠通知 | 已確認（2026-07-23） |
| D28 | 名額只計 COMMITTED；候補預設 FIFO，升補只發有期限 offer、本人接受後才 COMMITTED；名額下修不自動踢人 | 已確認（2026-07-23） |
| D29 | 名額採 `HARD_LIMIT／MANAGER_OVERRIDE`；超額需 scoped 權限、警示、理由與 audit，並以 `OVER_CAPACITY` 明確呈現 | 已確認（2026-07-23） |
| D30 | 逾期異動通知 v1 採 `OFF／IMMEDIATE`，啟用名額／候補時預設 IMMEDIATE；指定 recipients 收具名異動與前後統計，不含私人提醒／Knowledge／位置 | 已確認（2026-07-23） |
| D31 | 參加者互相可見只公開 active TENTATIVE／COMMITTED；候補／退出／被移除名單限 manager，候補者只看自己的順位；本人退出原因選填且未明確分享不得顯示 | 已確認（2026-07-23） |
| D32 | recurrence v1 支援 DAILY／WEEKLY／MONTHLY／YEARLY、interval、多 weekday、月／年日期與第 N／最後 weekday／最後一天、COUNT／inclusive UNTIL、排除／追加／單次 override；不含時／分／秒頻率 | 已確認（2026-07-23） |
| D33 | series edit/cancel 支援 THIS_OCCURRENCE／THIS_AND_FUTURE／ENTIRE_SERIES；本次及未來以 split series 建新 revision，過去 occurrence／audit 不回寫 | 已確認（2026-07-23） |
| D34 | recurrence rule 可掛 plan 或 activity，但 ancestry 最多一個 recurrence owner；其 nodes/reminder templates 依 occurrence 解析 | 已確認（2026-07-23） |
| D35 | participation/adoption 可選 occurrence 或 series；series adoption 只自動涵蓋已接受 rule revision 產生的未來 occurrence，新 node／rule revision 仍 review；單次略過不退出系列 | 已確認（2026-07-23） |
| D36 | registration scope 採 SERIES／EACH_OCCURRENCE，預設 EACH_OCCURRENCE；已有報名後變更 scope 必須 impact preview、revision 與 review | 已確認（2026-07-23） |
| D37 | timed recurrence 保存 LocalDateTime + ZoneId wall-clock anchor；無效月日／非閏年 2/29／DST gap 略過、overlap 取第一 offset，critical/locked invalid occurrence 額外確認 | 已確認（2026-07-23） |
| D38 | recurrence 使用 typed rule/exception/split lineage，不以 raw RRULE 為核心真相；查詢與 reminder 只做有上限的動態展開／rolling materialization，replay 零重複 | 已確認（2026-07-23） |
| D39 | external event invitation／email guest invitation、外部 RSVP 回寫與退信／身分對應不進第一個 release；先完成內部群組 participation／registration | 已確認（2026-07-23） |
| D40 | visibility 採 PRIVATE／SELECTED_MEMBERS／GROUP_VISIBLE；grantee 必須是 owning workspace active member，v1 不做跨 workspace 複製／直接分享 | 已確認（2026-07-23） |
| D41 | share scope 採 LIVE_WHOLE_PLAN／SELECTED_ACTIVITIES／SELECTED_NODES；whole-plan 自動含未來 descendants，selected scope 為 versioned snapshot、不自動擴張 | 已確認（2026-07-23） |
| D42 | effective ACL 採 additive grant、無 deny override；plan grant 向下適用，撤銷一筆不影響其他獨立 grant | 已確認（2026-07-23） |
| D43 | VIEWER／EDITOR 與 AUTHORITATIVE_EDITOR／PARTICIPANT_MANAGER 能力分離；第一版僅 plan owner 可 grant/revoke share，group admin 不自動管理 private plan | 已確認（2026-07-23） |
| D44 | selected node 只揭露最小 parent context；relative node 分享需 preview 一層 dependency minimal projection，不能揭露 base 時拒絕 | 已確認（2026-07-23） |
| D45 | core share 不含附件、私人 notes/Knowledge/tag graph、個人 route/location/reminder 或 roster；附件逐 asset、Knowledge 逐 excerpt、roster 依專屬 policy 授權 | 已確認（2026-07-23） |
| D46 | share grant/revoke/role change versioned、audit、idempotent並各通知一次；VIEWER 不自動訂閱 routine updates | 已確認（2026-07-23） |
| D47 | 撤銷 share 保留 recipient 已建立的最小 personal adoption/reminder snapshot但停止更新；active participant 的最後 access 只能保留 minimum access 或走 organizer removal | 已確認（2026-07-23） |
| D48 | ownership transfer 限同 workspace active member並採 OFFERED／ACCEPTED；接受前原 owner 負責，transfer 不改 participation/adoption/reminder/registration | 已確認（2026-07-23） |
| D49 | EventKit 延後至 iOS client；先用 EventKitUI／write-only，full access 只有真正雙向需求另案核准，裝置日曆不是 source of truth | 已確認（2026-07-23） |
| D50 | 第一版交付 authenticated one-time ICS snapshot export＋private import proposal；不含 webcal subscription、背景刷新、雙向同步或外部自動覆寫 | 已確認（2026-07-23） |
| D51 | export 限本人擁有／已採用 projection、單一 plan 或最長 366 天；預設 COMPACT，可選 ROUTE_AWARE 額外輸出本人已採用的 critical／route nodes | 已確認（2026-07-23） |
| D52 | export 不含 roster、他人狀態、私人 Knowledge／位置／未授權附件；僅 actor-owned reminders 可有損映射 VALARM，下載前顯示 loss 與不可撤回告知 | 已確認（2026-07-23） |
| D53 | ICS import 是 actor-private evidence＋逐項 proposal；明確選欄位並確認前，Calendar／Task／Reminder／Participation／Share／Knowledge fact 零 mutation | 已確認（2026-07-23） |
| D54 | METHOD、ORGANIZER、ATTENDEE、VALARM、URL／ATTACH 都不作命令；不建 membership／roster／RSVP、不抓外部 URI，alarm 只成為待確認個人提醒建議 | 已確認（2026-07-23） |
| D55 | 外部 UID／RECURRENCE-ID／SEQUENCE 只協助 scoped dedupe／revision proposal；replay materialize once，supported RRULE 才映射 typed rule，floating time 先確認 ZoneId | 已確認（2026-07-23） |
| D56 | ICS v1 預設每檔 5 MiB／10,000 VEVENT、export 最長 366 天與短效單次 artifact；Java bounded parser 驗證，LLM 不解析 ICS | 已確認（2026-07-23） |
| D57 | 第一版以安全搜尋＋typed 線上連結為 release gate，既有 StoredMedia attachment binding 在 cutover 前交付；color mutation 延後至 iOS UI | 已確認（2026-07-23） |
| D58 | search 支援 core text＋typed filters，逐 workspace 授權後合併；不索引 roster、私人 reminder/route/location、raw attachment 或未授權 Knowledge | 已確認（2026-07-23） |
| D59 | 每個 plan/activity/node v1 最多一個 primary online link，只允許 normalized HTTPS；不 fetch/redirect/autojoin，link 依 target visibility 且完整 URI 不進 log/snippet | 已確認（2026-07-23） |
| D60 | attachment 使用 typed binding 指向既有 actor-private StoredMedia、沿用格式／15 MiB／quota；binding 不等於分享，逐 asset grant，unlink/revoke/delete/replace 分離 | 已確認（2026-07-23） |
| D61 | category 是每 plan/activity 一個可分享搜尋的 normalized label；color 不具業務語意，第一版不提供，未來採 actor-private layer preference並保留非顏色提示 | 已確認（2026-07-23） |

## 6. 建議資料模型與不變量

實作時不得先固定 migration 版本；必須使用 repository coordinator 預約當下可用版本。

### 6.1 Aggregate 與資料表

建議 aggregate：

- `CalendarPlan`
  - workspace、owner actor、title、description、placement、default zone、location、visibility、
    lifecycle、optimistic version、created/updated time。
  - children：activities、plan-level nodes。
- `CalendarActivity`
  - plan、title、placement、location、category、display order、version。
  - 沒有 parent activity 欄位。
- `CalendarTimeNode`
  - plan、nullable activity、label、kind、criticality、adjustability、source、time expression、
    effective location、change policy、revision、status。
- `CalendarOnlineAccessLink`
  - plan/activity/node XOR target、normalized HTTPS URI、optional label、revision、status；每個 target
    最多一個 active primary link，不作 remote resource。
- `CalendarAttachmentBinding`
  - plan/activity/node XOR target、StoredMedia FK、display name/order、binding revision、status；
    binary、storage key 與 share grant 不放在 Calendar aggregate。
- `CalendarRecurrenceRule`
  - plan XOR activity owner、frequency、interval、day/month pattern、end condition、wall-clock anchor／
    all-day anchor、ZoneId、revision、split parent/boundary、status。
- `CalendarRecurrenceException`
  - rule revision、logical occurrence key、`EXCLUDED／ADDED／OVERRIDDEN`、replacement placement、
    source revision、status；同一 key 的例外 deterministic 合併。
- `CalendarAdoption`
  - plan、actor、status、availability impact、accepted plan revision、review state。
  - children：selected node revision references。
- `CalendarParticipation`
  - actor、plan/activity/occurrence scope、participation status、event participation policy snapshot、
    status origin、visibility、revision、opt-out acknowledgement、joined/withdrawn time。
- `CalendarOrganizerAssignment`
  - activity/occurrence scope、manager actor、`OWNER／PARTICIPANT_MANAGER` role、roster permission、
    notification recipient、status、version；負責單位顯示名稱不直接授權。
- `CalendarRegistrationPolicy`
  - activity/occurrence scope、opens/closes at + ZoneId、nullable capacity、late-join policy、waitlist／
    promotion／over-capacity／late-notification policy、version。
- `CalendarWaitlistEntry`／`CalendarWaitlistOffer`
  - participant、queue sequence、manual adjustment reason、offer/expiry/response state、version。
- `CalendarParticipationChange`
  - append-only actor/manager action audit，保存前後狀態、原因、target revision、idempotency identity。
- `CalendarNotificationPreference`
  - actor、plan/activity/occurrence scope、routine update subscription、status、version；不得覆蓋
    non-suppressible system message category。
- `CalendarReminderRule`
  - node revision、recipient actor、mode、offset／absolute time、delivery mode、status、version。
- `CalendarReminderOccurrence`
  - rule、resolved fire time、queue state、delivery idempotency key、attempt／terminal state。
- `CalendarShare`
  - plan、grantee actor、`VIEWER／EDITOR`、scope mode、share revision、status；grantee 與 plan 必須同
    workspace。
- `CalendarShareTarget`
  - share、activity/node target、target revision、dependency-minimum flag；selected scope 的 versioned
    snapshot，不使用無 FK 的 generic resource id。
- `CalendarShareContentGrant`
  - share、attachment/excerpt target、content kind、status、version；core visibility 不隱含內容授權。
- `CalendarOwnershipTransfer`
  - plan、from/to actor、`OFFERED／ACCEPTED／EXPIRED／CANCELED`、expiry、version、audit identity。
- `CalendarExternalImportBatch`
  - workspace、actor、private media/evidence reference、source fingerprint、parse state、limits snapshot、
    expiry、version；raw payload 不進 Calendar aggregate。
- `CalendarExternalImportItem`
  - batch、external UID／RECURRENCE-ID／SEQUENCE、normalized candidate、field selection、warnings、
    proposal state、nullable materialized plan FK、confirmation idempotency identity。
- `CalendarExportArtifact`
  - actor、requested projection scope、format profile、loss report、authorization snapshot、expires/used at、
    hashed single-use token、status；artifact 不是可訂閱 calendar source。
- `CalendarTaskBinding`
  - Task FK 與 activity/node FK；恰好一個 calendar target，保存 purpose、version 與 lifecycle。
- `CalendarKnowledgeAnnotationBinding`／`CalendarKnowledgeFactBinding`
  - 分別以真實 FK 綁 ObjectAnnotation／UserKnowledgeFact 與 plan/activity/node；不使用通用
    `resource_type/resource_id`。
- `CalendarKnowledgeExcerpt`
  - 本人明確批准、可供指定 share scope 查看之版本化摘錄；不複製整個私人知識或 tag graph。

### 6.2 必須由 DB 與 Java 共同守住的規則

- 所有 owned table 有 `workspace_id`，遵循既有 RLS pattern。
- actor-private adoption、rule 與 occurrence 必須有 actor application filter；同 workspace 不代表可互看。
- share grantee、owner transfer target 與所有 share target 必須和 plan 同 workspace；grantee 必須是 active
  member。跨 workspace actor、已離群 member 或猜測不存在 member 一律 fail closed 且不洩漏存在性。
- effective permission 只能由 active additive grants 聯集計算；plan grant 可向下、selected grant 不可
  形成 deny。grant/revoke 必須帶 share/plan version，replay 不得建立重複 target 或通知。
- LIVE_WHOLE_PLAN 可納入未來 descendants；SELECTED_ACTIVITIES／SELECTED_NODES 只讀 share revision
  的 target snapshot。新增 child 不得因名稱、parent 或同時間自動混入 selected scope。
- selected relative node 必須包含最多一層、已授權且足以重算的 dependency-minimum projection；無法安全
  揭露 dependency 時整筆分享 rollback，不保存半套 scope。
- 具名 roster 只能由 activity owner 或該 scope 的 active participant manager 查詢；回傳欄位採 allowlist，
  group admin／一般 editor／其他 participant 不得因此讀到私人 reminder、Knowledge、location 或聯絡資料。
- participant-visible roster 只含 policy 允許的 active TENTATIVE／COMMITTED display name；waitlist、
  withdrawn、removed、declined identity 與未分享 withdrawal reason 一律 manager-scoped。
- activity 與 node 以含 workspace／plan 的 composite FK 防止跨 workspace 或跨 plan 綁定。
- 相同 actor、node revision、提醒語意不得重複。
- 每位 actor／每個 node 最多 8 個 active personal reminder rules；每個 node 最多 8 個 active shared
  templates。ACK 重試／升級 occurrence 不另占 rule quota，歷史與 canceled rule 不計。
- Calendar reminder offset 只能小於或等於 0；節點之後的追蹤必須 materialize 成 Task，不得以正 offset
  calendar alert 繞過 Task lifecycle。
- placement union 必須恰有一種：timed interval、timed point、all-day。
- interval 的 end 必須晚於 start；all-day end date 採 exclusive end 並寫入 API contract。
- recurrence rule 必須恰屬於 plan 或 activity；同一 ancestry 最多一個 active recurrence owner，
  rule anchor 必須與 owner placement 同為 timed 或 all-day，不得混用。
- frequency 只允許 DAILY／WEEKLY／MONTHLY／YEARLY，interval／COUNT 必須為正整數，COUNT 與
  UNTIL 互斥；UNTIL inclusive。rule、RDATE/EXDATE-equivalent 與 override 產生重複 logical key 時
  只保留一個 occurrence，排除例外優先。
- THIS_AND_FUTURE 必須在 logical occurrence boundary 原子地終止舊 rule projection、建立新 rule
  lineage 與轉移未來適用 exception；過去 occurrence、registration、LifeRecord 與 audit 不得被改寫。
- series adoption 只能追隨本人接受的 rule revision；新增 node template、改 rule、split series 或
  registration scope change 不得沿用舊 consent，必須依影響進 review。occurrence skip 只建立本人
  exception，不得把 series participation 靜默改成 OPTED_OUT。
- SERIES registration 一個 COMMITTED 占一個 series seat；EACH_OCCURRENCE 逐 logical key 計 seat。
  scope 已有 registration 後不得直接更新欄位，必須 versioned impact command。
- recurrence local date-time 必須透過 ZoneRules 判斷 normal／gap／overlap。invalid date／gap occurrence
  不得計入 COUNT；overlap 使用第一 valid offset並保存解析證據。critical／locked invalid occurrence
  未取得額外確認前不得 publish rule revision。
- recurrence query 必須要求 bounded window／limit；persistence 不建立無限 occurrence。reminder
  materializer 以 rule revision + logical occurrence key + reminder rule revision 作 idempotency identity，
  並按 fire time 維持有限 horizon；exception、split 與 retry 不得漏投或重複投遞。
- relative expression 不得自我參照、不得成環；v1 base node 必須是 absolute／owner-boundary node，
  node-to-node dependency 最多一層。
- `LOCKED` node 不接受一般 move command；只能以明確 revision command 變更。
- `AUTHORIZED_AUTO_APPLY` 必須同時驗證 scoped authoritative permission、允許的客觀欄位與採用狀態；
  一般 editor、跨 scope actor 或非客觀欄位一律 fail closed。
- 自動套用、舊 occurrence 取消、相對 reminder occurrence 重建與 mandatory change notification
  必須具相同 revision idempotency identity；任何一步失敗整體 rollback。
- `AUTO_APPLIED_AWAITING_ACK` 不得投影或表達為本人同意、已讀或已確認。
- Task deadline、calendar placement 與 reminder rule 是三個獨立欄位／關係；任一 mutation 不得靜默改動另兩者。
- Task／Knowledge binding 必須有 workspace、actor application filter、真實 FK 與 exactly-one calendar target
  constraint；跨 actor／workspace、已封存 target 或未授權 excerpt 一律 fail closed。
- Knowledge binding 不授權執行內容，也不等同分享；任何可影響路徑或提醒的 knowledge 都須經 typed
  materialization command 與明確 consent。
- core share、attachment grant、Knowledge excerpt、roster visibility 與 private reminder 必須是不同
  authorization checks；任何 broad plan permission 都不得旁路 actor-private/application filter。
- Calendar 封存／取消只關閉或封存 binding，不 cascade delete Task／Knowledge；反向 lifecycle 亦同。
- share 不等於 adoption；adoption 不等於 reminder。
- membership／visibility、participation、watch subscription、node adoption、availability impact 與
  reminder 必須分開；任何一個狀態不得推導成其他狀態的使用者同意。
- 略過／退出必須帶 actor、workspace、target revision、impact digest 與 confirmation token；成功時本人
  participation、adoption、future occurrence cancellation 與 routine-message preference 原子更新，
  replay 不得重複產生取消、通知或 LifeRecord。
- `COMMITTED` 是唯一占用 capacity 的狀態；最後一席的並行請求必須以 DB constraint／lock 或等價
  concurrency control 決出唯一結果，另一筆依 policy 進 waitlist／request／rejected，不能偶發超額。
- 報名截止、offer expiry 與通知判斷一律使用注入的 `Clock`；target policy revision 改變時舊的
  confirmation／offer 失效，不能套用舊名額或截止規則。
- 本人退出不得被 manager veto；`WITHDRAWN_BY_USER` 與 `REMOVED_BY_ORGANIZER` 必須是不同 transition、
  audit origin 與通知文字，任何管理 API／LINE 指令不得代替本人同意。
- waitlist queue sequence 在同 activity/occurrence 唯一；手動調序需 scoped permission、理由與 audit。
  offer 只能鎖定一個候補 actor 並有期限；未獲本人接受不得建立 COMMITTED、adoption 或 personal reminder。
- capacity 下修低於 committed count 只能進 `OVER_CAPACITY`；不得依順位、時間或 manager 偏好自動移除
  現有 participant。MANAGER_OVERRIDE 超額必須明確警示、理由、confirmation 與 append-only audit。
- 截止後 participation mutation、capacity／waitlist state change、本人結果通知與已啟用的 manager
  notification 必須在同一 transaction 建立可靠 outbox records；重送不得重複計數、升補或投遞。
- 撤銷 share 時，recipient 已接受的 personal snapshot 轉為 source-access-revoked 並停止 source update；
  不刪其 adoption/reminder。附件/content grant 立即失效。若 recipient 仍 TENTATIVE／COMMITTED，
  transaction 必須保留 participant-minimum access 或同時完成 organizer removal 與 mandatory notification。
- ownership transfer target 必須明確 accept；接受前 from actor 仍是 owner。transfer、owner group-leave
  gate 與 audit 原子，且不得改變任何 actor 的 participation/adoption/reminder/registration。
- EVENT_REGISTRATION 文件／OCR 是 evidence，不是群組 roster command；未精確匹配 activity、未授權或
  未明確 materialize 時，participation／capacity／waitlist mutation 必須為零。
- 跨群組 read aggregation 只能逐 workspace 進入 tenant scope 並驗證 membership；任何部分失敗須
  明示哪個群組暫時不可用，不得把缺資料冒充完整總覽，也不得洩漏未授權群組是否存在。
- 取消／封存 plan 不硬刪歷史或外部 evidence。
- 所有時間與 timeout 使用注入的 `Clock`。
- external import batch／item 與 export artifact 一律 actor-private；同 workspace actor、group admin、
  background worker 都不得旁路 application filter／RLS 讀取 raw evidence、proposal 或 download token。
- 外部 UID／RECURRENCE-ID／SEQUENCE 不得作 global unique key，也不能授權或直接定位別人的 Calendar。
  dedupe identity 必須含 workspace、actor、import source 與 normalized fingerprint。
- import proposal confirmation 必須 revision-bound、field-scoped、idempotent；每個 item 最多連到一個
  materialized target。parse／preview、較高 SEQUENCE、METHOD:CANCEL 或 provider replay 都不得直接
  update/delete Calendar、Task、Reminder、Participation、Share 或 Knowledge fact。
- export generation 與 download 都要驗證 actor、scope、adoption／ownership 與 expiry；single-use token
  只保存 hash，使用／過期／撤銷後不得重播。不得建立永久 public/webcal URL。
- ICS parser 必須在 5 MiB、10,000 VEVENT、366 天 export window 與 bounded recurrence／line limits
  內 fail closed；URL／ATTACH 不觸發 outbound network 或 asset materialization。
- search 必須先套 actor／workspace／share/content authorization再產生 result/snippet；跨 workspace
  result 是多個 authorized query 的 bounded merge，不得使用 SYSTEM scope 先全域查後過濾。
- online access link 只接受 normalized HTTPS、每個 target 最多一筆 active link；不得在 log、
  LifeRecord、notification diagnostic、search snippet 或 provider error輸出完整 URI/query secret。
- attachment binding 的 target 與 StoredMedia 必須同 workspace 且由 binding actor 可讀；binding、
  content grant、unlink、media delete 各有獨立 version/lifecycle。share grant 不得推導 content grant，
  revoke content grant 後 recipient download 必須立即 fail closed。
- category normalized label 不是 enum、權限、狀態或顏色；color 不得參與 domain transition、search
  authorization、conflict、criticality或 participation 判斷。

### 6.3 Domain event

使用者可感知的正式事件應接入 LifeRecord／tag graph，例如：

- plan published／canceled／archived。
- recurrence rule published／split；occurrence overridden／excluded／added。
- node revision accepted。
- authoritative node revision auto-applied／acknowledged。
- actor adopted／declined selected constraints。
- actor registration requested／waitlisted／committed／withdrawn，或 organizer removed actor。
- waitlist offer created／accepted／expired；capacity entered／resolved over-capacity。
- share granted／role changed／revoked；ownership transfer offered／accepted／canceled。
- personal reminder rule created／canceled。

內部 resolution、projection rebuild、queue claim、純 binding/view、開發回饋與純 focus transition
不記為生活事件。單純 ICS parse／preview／export 亦不記為生活事件；使用者確認匯入後只由實際建立的
Calendar／Reminder domain event 記錄一次，不另建立重複的「匯入」生活事件。

## 7. 模組與服務責任

### 7.1 建議套件

- `calendar.domain`：plan、activity、node、recurrence/exception/split、participation/registration、
  capacity/waitlist、adoption、share、reminder rule 的不變量與 value object。
- `calendar.application`：transaction boundary、authorization、versioning、registration、waitlist、
  revision、adoption orchestration。
- `calendar.persistence`：repository 與 projection query。
- `calendar.query`：日／週／月／列表的 read model。
- `planner`：新增只接 typed adopted constraints 的 route policy；不讓新 domain 依賴 planner。
- `integration.notification`：重用既有 outbox contract。
- `api.calendar`：只處理 protocol／DTO／ETag，不放商業邏輯。
- `intent`：只輸出 typed command；Java resolver 選 target、驗證時間與執行 mutation。

### 7.2 只在責任真的重複時使用的模式

- Strategy：`TimeExpressionResolver` 對 absolute／boundary offset／node offset。
- Strategy：`RecurrenceExpander` 只對 typed frequency/pattern 產生 bounded logical occurrences。
- Policy：`NodeAdjustmentPolicy`、`RegistrationPolicy`、`CapacityPolicy`、`WaitlistPromotionPolicy`、
  `AdoptionPolicy`、`PersonalRoutePolicy`。
- Outbox：提醒與 shared change notification 的可靠投遞。
- Anti-corruption adapter：legacy schedule、ICS 與新 model 之間的有損 projection；EventKit adapter
  留在未來 iOS client，後端只提供經授權的 REST contract。
- CQRS-lite query projection：只有日曆視圖查詢證明 aggregate query 過重時才加，不先上 event sourcing。

禁止：

- `resource_type/resource_id` 無 FK 的任意 polymorphic binding。
- 為了「以後可能有更多層」先做 recursive generic tree。
- 以 prompt 或 regex 執行時間運算、cycle detection、authorization 或 mutation。
- 把所有 calendar 行為塞進單一 service 或 controller。

## 8. Legacy 過渡、切換、退役與旅遊專案邊界

### 8.1 Cutover 前的 additive-only 原則

Calendar core 開發與驗收期間：

- 新表使用 `calendar_*` 前綴；不得 alter legacy `schedule_item`／`reminder` 語意欄位。
- 新 API 使用獨立 namespace，例如 `/api/v2/calendar/...`。
- 新 Intent 必須是新 type、handler、capability entry 與 regression test。
- 不 dual write；同一 inbound 只能建立 legacy 或新版其中一套。
- actor-level feature preference／pilot flag 決定預設入口；不使用單一精確句子 hard-code routing。
- legacy 與新版同名同時段資料不自動合併。

### 8.2 Hard cutover 與 legacy retirement

使用者已於 2026-07-23 確認：現有系統未正式上線、只有本人使用，legacy 資料是可捨棄的測試資料；
最終產品不保留 legacy schedule，也不投資正式資料遷移或長期 unified view。

執行契約：

1. Cutover 前兩套入口與資料完全隔離；新版 route 只讀新版 adoption／node，不讀 legacy constraint。
2. 不建立 legacy-to-new migration、projection、dual-write 或正式 unified calendar view；需要測試時使用明確
   legacy／v2 入口，不把過渡工具變成產品契約。
3. 新版通過功能、RLS、提醒、路徑、對話、效能、完整 Maven 與 LINE E2E gate 後，先把所有自然語言與
   API 預設入口切到新版，將 legacy 暫時設為無新寫入，完成 cutover rehearsal 與短期驗證。
4. 實際移除前必須列出 legacy schedule 的 code、API、Intent、capability、FK、LifeRecord、planner、
   travel 與測試依賴，證明每個必要能力已移植或明確淘汰。
5. Legacy retirement 只移除舊 Schedule 產品線與 schedule-specific 資料；仍被 Task 等功能使用的
   generic reminder queue、Task reminder、Clock、workspace/RLS 與 LifeRecord 基礎設施不得誤刪。
6. 刪除 code／API／Intent 與 drop legacy table 必須是獨立、forward-only Flyway release；保留 migration
   歷史，不改寫舊 migration。正式執行前先備份，並再次取得使用者對精確刪除清單的 destructive approval。
7. Retirement 後 capability catalog、路由、repository 與 production tests 不得再引用 legacy schedule；
   從空 DB 與既有 DB 兩條 migration path 都必須通過。
8. Cutover 前可用 feature flag 回退到 legacy；完成 destructive retirement 後只能以已驗證的備份／部署
   rollback 復原，不得假稱仍可一鍵切回。

### 8.3 進行中的旅遊計畫

現行旅遊 active plan 的 schedule ownership／same-Project conflict 與本計畫有語意衝突。

建議：

- Conversation Focus、Project、evidence 與 checklist 等不依賴正式 itinerary 的前置工作可繼續。
- `[2026-07-25 使用者已確認]` Travel 3B 拆為 3B-A one-off
  `ProjectCalendarPlanBinding` core 與 3B-B recurrence/copy/split propagation。3B-A 已授權在
  V75 穩定、CalendarPlan cancel/archive lifecycle 完成、Project binding interlock 通過後啟動；
  3B-B 維持 `BLOCKED_BY_CALENDAR_WHEEL_10`。
- Calendar Wheel 10 只解除 3B-B 的 recurrence 契約依賴，不代表 Travel 3B-B 已通過；不得擴充
  legacy Schedule itinerary、建立 transition binding 或自動開始 Travel 3C。
- 新 calendar core 穩定後，旅遊 evidence 只能投影到 `CalendarPlan/TimeNode`；不再新增以 legacy
  `ScheduleItem` 作正式 itinerary 的過渡版本。
- 不在兩份 active plan 中同時修改同一 schedule/reminder 核心檔案。

### 8.4 既有規劃系統與知識紀錄收斂契約

`[已確認 2026-07-23]`。2026-07-23 靜態盤點基線顯示，至少 63 個 production 檔與 56 個 test 檔直接引用
`ScheduleItem`／`ScheduleService`，範圍包含 intent、planner、reminder、conversation、family、event
與 planning。Legacy retirement 必須有獨立的「規劃系統收斂」舵輪，不能留到 drop table 前才處理。

| 既有能力 | 最終定位 | 是否併入 calendar aggregate | 必要調整 |
| --- | --- | --- | --- |
| 各領域 Draft／draft retention | 保留為缺資料、待確認或待授權的暫存狀態 | 否 | 只能經 typed materialization 建立 Task、Calendar 或 Knowledge；draft 不進個人路徑 |
| Task／DeferredTask | 保留工作完成、相依與 deadline source of truth | 否 | deadline、實際執行時段與提醒時間分離；執行時段以 typed binding 連到 CalendarActivity／TimeNode |
| FlexibleDayTaskPlan | 保留為 Task 的日期範圍與建議提醒 | 否 | free-slot 查詢改讀新版 PersonalRouteProjection；未排入時段前不形成 calendar constraint |
| 舊 `SCHEDULE_REMINDER` 類別 | 退役 | 是，依真實語意拆分 | 無完成狀態的日曆時點改為 CalendarTimeNode；有待完成行為者改為 Task + Task reminder |
| 舊 `ScheduleItem` | 完整退役 | 由新版取代 | 能力移植到 CalendarPlan／Activity／TimeNode／Adoption，不遷移測試資料 |
| Task Reminder／ACK／escalation | 保留 Task 閉環 | 否 | 與 CalendarReminderRule 分開；只共用可靠 delivery／outbox／channel adapter |
| Feasibility／FreeSlot／Planner | 保留規劃責任但替換輸入 | 否 | 不再讀 ScheduleItemRepository，改讀 actor 已採用的新版 route/constraint projection |
| Project | 保留 root work 與 scope | 否 | 以真實 FK typed binding 綁 Draft、Task、CalendarPlan 與 Knowledge，不建立通用字串 resource id |
| Knowledge／ObjectAnnotation／UserKnowledgeFact | 保留獨立知識 source of truth，且必須能與 calendar 整合 | 否 | 以 typed knowledge binding 關聯 plan／activity／node；知識仍是 evidence，不直接執行 mutation |
| LifeRecord／tag graph | 保留跨領域生活事件與查詢 | 否 | 接入新版 calendar domain event；單純讀取／綁定不重複寫生活事件 |

建議最終使用者可感知的規劃類型收斂為：

1. 草稿：還缺資料、確認或授權。
2. 待辦事項：有完成狀態，可有 deadline、相依與 Task reminder。
3. 行程計畫／活動／時間節點：日曆、共享、採用與個人路徑。
4. 知識紀錄：可重用的事實、註記與 evidence。

「提醒」不再是與上述四類並列的規劃項目；它是 Task 或 CalendarTimeNode 上的通知規則。現有
`PlanningItemType.SCHEDULE_REMINDER`、`PlanningItemClassifier` 與要求行程先通過撞期才成立的
`PlanningItemTransitionPolicy` 必須在 cutover 前改寫，不能只增加新版 enum。

Task 必須進一步拆清楚三個時間語意：`deadline` 是最晚完成限制；calendar placement 是預計實際執行
時段；reminder rule 是通知時間。三者可各自存在，改動其中一項不得靜默搬動另外兩項。

#### 8.4.1 Knowledge × Calendar 整合契約

- 簡短且只屬單一行程的說明留在 plan/activity/node description；可跨行程重用或需獨立搜尋的內容才建
  Knowledge record。
- 不沿用 `ObjectAnnotation.TargetType + targetId` 擴充 `CALENDAR`；新版使用含真實 FK、workspace 與
  actor boundary 的 typed binding，且明確綁 plan、activity 或 node。
- Knowledge 預設 actor-private。分享 CalendarPlan 不自動分享完整知識、tag graph、原始附件或其他私人
  facts；若要讓參與者看見，只分享經本人確認的版本化 excerpt/snapshot。
- Knowledge retrieval 結果是 evidence，不是指令。內容即使寫著「把活動取消」也不能直接 mutation。
- 若知識代表可執行規則，必須由 Java 驗證並經明確 materialization consent 轉成對應結構：
  - 要做的事 → Task；
  - 時間／地點約束 → CalendarTimeNode 或 PlanningPreference／BufferRule；
  - 通知需求 → 本人擁有的 Task 或 Calendar reminder rule。
- Knowledge 更新時，既有 calendar binding 依版本進 review；不得靜默改變已採用路徑。權威變更仍只能
  走 D14，不能藉更新知識繞過授權。
- 取消／封存 Calendar 不刪除 Knowledge，只解除或封存 binding；封存 Knowledge 也不刪 Calendar。
- Calendar 查詢只載入同 actor 或明確獲授權的 bounded knowledge，不把整個私人知識庫塞入 prompt／回覆。
- Calendar 正式建立、採用、取消、完成與權威變更接入 LifeRecord／tag graph；單純檢視或引用知識不重複
  建立生活事件。

## 9. API、對話與使用者回覆契約

### 9.1 候選能力

使用者確認後再固定 Intent Type；候選包含：

- 建立／更新／取消／封存新版行程計畫。
- 新增／更新／移除子活動。
- 新增／調整／修訂時間節點。
- 建立／修改／取消 recurrence series，並明確選擇本次、本次及未來或整系列 scope。
- 排除／追加／override occurrence；單次略過或恢復 series participation。
- 新增／取消／列出節點提醒。
- 分享 whole plan／selected activities／selected nodes、授權／撤銷 VIEWER/EDITOR、列出 effective access。
- 提出／接受／取消 ownership transfer；撤銷最後 participant access 時走明確 resolution。
- 參加／退出活動、採用／取消採用計畫或節點。
- 設定報名期間、名額、截止後加入、候補、升補、超額與逾期異動通知政策。
- owner 指派 participant manager；授權 manager 查具名 roster／統計、審核 request、發候補 offer、
  記錄 organizer removal 或以理由確認 manager override。
- 查詢某人已採用路徑與可行性。
- 建立一次性 ICS snapshot export、查看 loss report、領取短效 artifact。
- 上傳 ICS、查看 actor-private import preview、選擇欄位、確認／取消 proposal；EventKit 操作在
  Backend／LINE 明確回覆需由未來 iOS client 完成。
- 依 keyword、日期、群組、狀態、參加／採用、criticality、category 搜尋並取得 bounded results。
- 新增／替換／移除 primary online link；綁定／解除／列出附件，並逐 asset 授權／撤銷分享。
- 列出指定日期的新版 calendar；cutover 前 legacy 查詢仍走明確舊入口，不建立長期 unified contract。

任何新增 Intent 必須同批加入 domain handler、`conversation-capabilities.txt` 與 regression。

### 9.2 LLM 可做與不可做

LLM 可輸出：

- operation、title、自然語言日期／時間／週期文字、location text、relative offset text、
  criticality／adjustability、recurrence frequency/pattern/edit-scope、share target/scope/role、
  registration policy 候選、search text/filter 候選、online-link／attachment target、export
  profile／scope、import proposal selection、引用選擇與缺欄位。

Java 必須決定：

- 實際 target、actor／workspace／participant-manager authorization、時區、Instant、offset、cycle、
  node/policy/rule/share revision、effective permission／dependency closure／content boundary、
  recurrence expansion／exception／split、DST validity、deadline boundary、capacity count、waitlist
  sequence／offer expiry、route result、reminder fire time、mutation count、idempotency 與公開回覆狀態。
- ICS lexical parse、component／property allowlist、size/count/line/recurrence bounds、TZID／VTIMEZONE、
  external identity scope、loss mapping、artifact expiry／single-use 與 proposal materialization；
  `METHOD／ATTENDEE／VALARM／URL／ATTACH` 不能由 LLM 或檔案內容轉成業務命令。
- search authorization／bounded merge／ranking／pagination、unique mutation target、HTTPS normalization、
  target-link cardinality、StoredMedia ownership／content grant／download、category normalization；
  不能由 LLM 直接查 DB、挑第一筆同名結果或決定附件可見性。

### 9.3 公開回覆

回覆先說：

1. 建立／修改了什麼，或為何尚未異動。
2. 解析後的計畫、節點與提醒實際時間。
3. 重疊只是資訊，還是已採用路徑存在風險。
4. 報名／候補／退出實際狀態；若是管理操作，明示名額與候補變化且不冒充參加者同意。
5. 缺哪個使用者決定。

不得顯示 DB ID、UUID、workspace／actor／message ID、Intent enum、class／package、SQL、migration、
stack trace、provider error 或 router reason。

## 10. Sol Medium 分階段開發計畫

每一階段都要紅燈測試先行；未過 gate 不得進下一階段。

目前舵輪：`9 — IN_PROGRESS`。舵輪 0–8 已通過；以下只回填實際證據，
不把預期數字寫成已通過：

| 舵輪 | 狀態 | 實際 tests／gate evidence | 剩餘 blocker |
| ---: | --- | --- | --- |
| 0 | COMPLETED | 決策／scenario／holdout／Travel stop 稽核通過；baseline 24 tests、0 failure、0 error、0 skipped | — |
| 1 | COMPLETED | red test compile failure 已證明；最終 domain gate 11 tests、0 failure、0 error、0 skipped；legacy／隔離 regression 24/24 | — |
| 2 | COMPLETED | V66 forward-only migration；Calendar wheel 1–2 合併 16/16；legacy／隔離 regression 24/24 | — |
| 3 | COMPLETED | V67、atomic graph／concurrent idempotency／REST v2；Calendar wheel 1–3 24/24；legacy／隔離 regression 24/24 | — |
| 4 | COMPLETED | V68、rule／occurrence／outbox；Calendar wheel 1–4 29/29；legacy＋Task reminder regression 39/39 | — |
| 5 | COMPLETED | V69、versioned adoption／Java route assessment；Calendar wheel 1–5 32/32；legacy feasibility regression 54/54 | — |
| 6 | COMPLETED | actor-scoped query／feature flag pilot；Calendar wheels 1–6 56/56；legacy regression 94/94 | — |
| 7 | COMPLETED | V70/V71、Task/Planning/Planner convergence；完整功能 gate 51/51；legacy regression 101/101 | — |
| 8 | COMPLETED | V72–V82；精準功能／隔離／RLS／neighbor／latency gate 全通過；sealed holdout 16 cases／19 turns；root regression 1417 tests、0 failure、0 error、18 skipped | — |
| 9 | IN_PROGRESS | W9-A／W9-B／W9-C／W9-D PASS；latest V87；W9-D security/neighbor 119/119、root regression 1514 tests／0 failure／0 error／18 skipped | W9-E |
| 10 | NOT_STARTED | — | 舵輪 9 |
| 11 | NOT_STARTED | — | 舵輪 10 |
| 12 | REQUIRES_DESTRUCTIVE_APPROVAL | — | 舵輪 11＋精確刪除清單／備份／復原演練＋使用者當輪批准 |

### 舵輪 0：決策 freeze 與 scenario manifest

目標：

- 使用者逐項拍板 D01–D61 與第 16 節問題。
- 登記 active index，將 durable decisions 寫入 current decisions。
- 固定 repair／permanent regression／sealed holdout manifest。
- 記錄 dirty worktree 基線與正在進行的 travel/conversation 變更；不覆寫。

本輪 allowlist：

- 本 active plan、`docs/exec-plans/active/index.md`、`docs/decisions/current.md`、
  `docs/architecture.md`、`docs/development-plan.md`。
- 如需 machine-readable manifest，只能新增 `src/test/resources/calendar/` 下不含真實個資、原始
  對話或 sealed oracle 的 scenario metadata；不得新增 production Java、Flyway migration、API、
  Intent、capability entry、disabled test 或 feature flag。
- Travel active plan 只可核對已存在的 3B Calendar integration hard stop，不得修改其 runtime scope、
  測試 expected 或繼續 3B。

Gate：

- 沒有未決 P0。
- 每個需求可追到 scenario 與測試層。
- 明確決定 travel schedule-dependent phases 是否暫停。
- D01–D61／C01–C145 連續、無重複、全部決策為已確認；public holdout 只列 variation axes，不揭露
  evaluator fixture／oracle。
- 以下 legacy／隔離 baseline 全綠，並把實際 tests/failures/errors/skipped 回填本文件；本輪不得以
  文件變更解釋既有 runtime failure：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test
```

出口：正式文件與 machine-readable metadata（若有）一致、dirty baseline 已記錄、Travel 3B
維持停止；然後才可開始舵輪 1 的 red tests。

#### 舵輪 0 實際 gate evidence（2026-07-23）

- 啟動 dirty baseline：`AGENTS.md`、`docs/agent-context/index.md`、`docs/architecture.md`、
  `docs/decisions/current.md`、`docs/development-plan.md`、`docs/exec-plans/active/index.md`、
  `docs/exec-plans/active/travel-project-terra-high-development-test-plan.md` 已修改；本計畫與
  `calendar-plan-v2-sol-medium-prompt.md` 為 untracked。全部視為既有變更保留，未執行
  reset、checkout、stash、clean、搬移或覆寫。
- D01–D61：61 筆、連續、無重複；全部狀態為已確認。C01–C145：145 筆、連續、無重複。
- 第 16 節無未決 P0；public sealed holdout 只列 variation axes，未放 evaluator fixture 或 oracle。
- active index、current decisions、development plan 與 Travel active plan 一致；Travel 3B 維持
  `BLOCKED_BY_CALENDAR_V2`，未修改 `schedule_item` ownership 或啟動 project-scoped legacy
  Schedule CRUD。
- 未新增 machine-readable metadata；`src/test/resources/calendar/` 在本輪啟動時不存在，
  正式 scenario matrix 已足以作 public manifest。
- 實際命令：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test`
  （以本機 OpenLogic Java 21 設定 `JAVA_HOME`）。
- 結果：exit code 0；24 tests、0 failures、0 errors、0 skipped；耗時 71.6 秒。
- 前兩次環境嘗試未進入測試：一次工具前景 timeout；一次 coordinator metadata sandbox denied；
  取得必要權限後首次 Maven 啟動因 `JAVA_HOME` 未設定而 exit 1。這些不計為測試案例結果，
  最終以相同 repository-safe wrapper 與相同測試參數完成上列綠燈 baseline。
- 未測路徑：production Calendar v2、API v2、LINE Calendar v2、migration、RLS 與 live provider
  尚未實作，故本輪未執行；不得由舵輪 0 baseline 推定其通過。

### 舵輪 1：時間代數與純 domain

執行契約（2026-07-23）：

- 唯一目標：建立 Calendar v2 placement、relative node dependency、reminder offset 與一層
  plan/activity aggregate 的純 domain foundation。
- non-goals：不建 migration、JPA、repository、API、Intent、feature flag、LifeRecord adapter，
  不修改 legacy Schedule／Reminder。
- 候選 production：`calendar/domain`；候選 tests：`calendar/domain` 的
  `CalendarPlanTest`、`CalendarTimeNodeTest`、`CalendarReminderRuleTest`；migration：無。
- resource claims：worktree source 只寫上述新 package 與本 active plan；main Maven target 由
  `mvn-safe.ps1` 單一 writer 管理；未取得 Git index、Flyway、shared runtime 或 fixed-port claim。
- validation：先確認 test compile red，再跑三個 domain test class；最後重跑舵輪 0
  legacy／隔離 baseline。未執行 live model／LINE。

候選範圍：

- 新 `calendar/domain`。
- `CalendarPlacement`、`TimeExpression`、`CalendarTimeNode`、plan/activity aggregate。

Gate：

- 使用者範例精確得到 11:20、11:20、11:05。
- criticality／adjustability 正交。
- interval／point／all-day／DST／跨日／cycle／一層 activity／一層 relative dependency 全部通過。
- 重疊建立為合法 domain case。

#### 舵輪 1 實際 gate evidence（2026-07-23）

- red test：先新增三個 domain test class；格式 gate 修正後，以
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest" test`
  執行，test compile 因 `CalendarPlan`、`CalendarPlacement`、`CalendarTimeNode`、
  `CalendarTimeResolver`、`CalendarReminderRule` 等 production types 尚不存在而 exit code 1。
- 最小 production implementation 位於新 `calendar/domain`，沒有 Spring、JPA、repository、
  provider 或 legacy Schedule dependency。plan 與一層 activity 都可擁有多個 nodes；activity
  無 child API 語意，重複 owner-local node id fail closed。
- `CalendarPlacement` 以 typed union 分開 timed interval、timed point、all-day；interval 使用
  `Instant + ZoneId` 且 end 必須晚於 start，all-day 只保存 `LocalDate` exclusive range。
- `Criticality` 與三態 `Adjustability` 為獨立欄位，六種組合皆有 regression；重疊只回報結果，
  不阻止 aggregate 建立。
- `TimeExpression` 支援 absolute、owner START／END relative offset，以及最多一層 node-relative
  dependency；self、unknown base、第二層／cycle 皆拒絕。DST gap 拒絕，overlap 取第一 valid
  offset；跨午夜 interval 有明確 regression。
- C01/C07 的 deterministic evidence：11:30 owner start + `-10m` 得 11:20；node 當下與 `-15m`
  reminders 得 11:20／11:05；owner 移到 12:00 後重算為 11:50／11:35。
- 最終 domain command：同上；exit code 0；11 tests、0 failures、0 errors、0 skipped；耗時
  70.0 秒。早一版 7/7、補強跨日／正交後 10/10 也通過，但不取代最終 11/11 gate。
- regression command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test`
  ；最終 exit code 0；24 tests、0 failures、0 errors、0 skipped；耗時 79.2 秒。
- regression 首次嘗試為 24 tests、0 failures、1 error、0 skipped；唯一 error 是 Docker daemon
  未啟動，`WorkspaceRlsIntegrationTest` 在 ApplicationContext 前找不到 Testcontainers environment。
  啟動既有 Docker Desktop、確認 daemon 28.3.2 ready 後以相同命令重跑全綠；未 clean、prune、
  FLUSHALL 或修改 test expected。
- performance sample：domain gate 11-case Maven wall time 70.0 秒（含 build lifecycle，非
  user-visible latency）；本輪沒有 API／LINE path，因此 low/medium user-visible P95 未測且不推定。
- 未測／剩餘：persistence、composite FK、actor filter、PostgreSQL runtime-role RLS、API、Intent、
  LINE、reminder worker、route 與 live provider 均屬後續舵輪。

舵輪 1 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "1",
  "status": "PASS",
  "approvedDecisions": ["D01", "D02", "D03", "D04", "D10", "D11", "D18", "D19"],
  "modifiedFiles": [
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/domain/",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/domain/",
    "docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md"
  ],
  "migrationReservations": [],
  "tests": [
    {"command": "mvn-safe CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest", "passed": 11, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-0 legacy/isolation baseline", "passed": 24, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["main Maven target"],
  "remainingWork": ["wheel 2 persistence, Flyway, composite ownership, actor filter and RLS"],
  "nextAction": "audit current Flyway reservations and wheel-2 persistence/RLS patterns before reserving a version",
  "risks": ["Docker Desktop must be ready for Testcontainers", "all persistence and user-visible paths remain unimplemented"],
  "userDecisionRequired": []
}
```

### 舵輪 2：Persistence、Flyway、RLS 與 actor boundary

執行契約（2026-07-23）：

- 唯一目標：為 Calendar plan/activity/node 建立 forward-only persistence、composite ownership、
  application actor filter、optimistic version 與 PostgreSQL runtime-role RLS。
- non-goals：不修改 legacy schema／語意，不做 API、Intent、sharing workflow、adoption、
  reminder worker、route、recurrence、ICS、Task／Knowledge binding 或 Travel 3B。
- 候選 production：`calendar/persistence`；候選 tests：
  `CalendarPersistenceIntegrationTest`、`CalendarRlsIntegrationTest`、`CalendarMigrationTest`；
  migration：coordinator 選定的
  `V66__create_calendar_core.sql`。
- resource claims：依固定順序取得 worktree source 與 `repo/flyway-sequence:main/V66`
  reservation；source operation `e3ef8830-97df-4c4b-8d29-7e59426df90c`、Flyway operation
  `8ef871e0-bb6b-4388-80fa-270a3bcd4bd5` 均為 `READY` 並正常釋放。main Maven target 與
  Docker capacity由 `mvn-safe.ps1`／Testcontainers gate 管理；不取得 Git index 或 shared
  runtime lifecycle claim。
- validation：先跑缺少 V66／persistence types 的 red tests；再驗證空 DB full migration、
  V65→V66 existing schema、legacy table fingerprint、JPA mapping、composite FK、unique、
  optimistic version、同 workspace 不同 actor、跨 workspace、viewer/editor 與 SYSTEM background
  runtime-role fail closed；最後重跑舵輪 0 baseline。

候選範圍：

- 預約的新 migration。
- `calendar/persistence` 與對應 integration tests。

Gate：

- migration 從空 DB 與現有 schema 均可 forward-only 套用。
- legacy tables schema 與行為未修改。
- 同 workspace 不同 actor、跨 workspace、viewer／editor、background worker RLS 全部 fail closed。
- composite FK、unique、optimistic version、owner filter 有實際 PostgreSQL 證據。

#### 舵輪 2 實際 gate evidence（2026-07-23）

- red test：先新增 `CalendarMigrationTest`、`CalendarPersistenceIntegrationTest`、
  `CalendarRlsIntegrationTest`，以
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarMigrationTest,CalendarPersistenceIntegrationTest,CalendarRlsIntegrationTest" test`
  執行；test compile 因 `calendar.persistence` package 與所有 entity/repository types 尚不存在而
  exit code 1。
- coordinator 從實際最高 migration V65 計算 V66；worktree source 與
  `repo/flyway-sequence:main/V66` reservation 均 `READY`，無競爭、stale takeover 或 rename
  published migration。
- `V66__create_calendar_core.sql` 只新增 `calendar_plan`、`calendar_activity`、
  `calendar_time_node`；沒有 ALTER／DROP legacy objects。V65→V66 前後 `schedule_item` 的
  column name/type/nullability fingerprint 完全相同。
- plan/activity 使用 typed placement union DB checks；activity→plan、node→plan、
  optional node→activity、node-relative base 都用含 plan/workspace/actor 的 composite FK。
  node key 在同 actor／plan 唯一，self relative reference由 Java與 DB共同拒絕。
- 三表皆有 immutable `workspace_id`／`created_by_user_id`、`@Version`、application repository
  workspace＋actor filter，以及 `ENABLE/FORCE ROW LEVEL SECURITY`。runtime test role 為
  `NOLOGIN NOSUPERUSER NOBYPASSRLS`。
- owner runtime context 對 plan/activity/node 分別只見 1/1/1；同 workspace peer（即使未來作
  viewer/editor 候選）、跨 workspace actor 與 SYSTEM background 對三表皆見 0，越權
  update/delete 為 0 rows，偽造 owner insert 被 PostgreSQL 拒絕。現階段尚未實作 share grant，
  所以 viewer/editor 不會因 workspace membership 自動取得存取。
- composite FK 實際拒絕把第二個 plan 的 node 綁到第一個 plan activity；duplicate node key
  實際由 PostgreSQL unique 拒絕；兩個 detached plan version 中只有先提交者成功，stale update
  被 optimistic locking 拒絕。
- 第一次 5-test integration run 為 4 pass／1 error：獨立 migration fixture 缺既有 V60 的
  non-secret test placeholders，V66 尚未執行。比照既有 migration test 補固定測試值後，
  同命令 5 tests、0 failures、0 errors、0 skipped，耗時 108.6 秒；補強 unique／三表 RLS 後
  再跑 5/5，耗時 109.0 秒。
- 最終 Calendar wheel 1–2 command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest,CalendarMigrationTest,CalendarPersistenceIntegrationTest,CalendarRlsIntegrationTest" test`
  ；exit code 0；16 tests、0 failures、0 errors、0 skipped；耗時 76.9 秒。
- 最終 legacy／隔離 regression command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test`
  ；exit code 0；24 tests、0 failures、0 errors、0 skipped；耗時 76.9 秒。
- performance sample：integration lifecycle wall time 如上，並非 user-visible latency。本輪沒有
  API／LINE/live provider path；Docker Desktop 28.3.2 為 Testcontainers 已啟動且未由本輪停止，
  未執行 clean、prune、volume removal 或 shared data cleanup。
- 未測／剩餘：sharing authorization 的正向 viewer/editor access、CRUD transaction/idempotency、
  API、Intent、reminder、route、recurrence 與 live paths 屬後續舵輪；不得由 actor-private
  foundation 推定完成。

舵輪 2 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "2",
  "status": "PASS",
  "approvedDecisions": ["D01", "D02", "D03", "D04", "D09", "D10", "D11"],
  "modifiedFiles": [
    "src/main/resources/db/migration/V66__create_calendar_core.sql",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/persistence/",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/CalendarMigrationTest.java",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/CalendarPersistenceIntegrationTest.java",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/CalendarRlsIntegrationTest.java",
    "docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md"
  ],
  "migrationReservations": ["main/V66: READY, released"],
  "tests": [
    {"command": "mvn-safe Calendar wheel 1-2", "passed": 16, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-0 legacy/isolation baseline", "passed": 24, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main/V66 Flyway reservation", "main Maven target"],
  "remainingWork": ["wheel 3 application CRUD, revision, idempotency and REST v2"],
  "nextAction": "write wheel-3 application/API red tests before production implementation",
  "risks": ["share-positive authorization is intentionally deferred", "Docker Desktop remains a shared retained service"],
  "userDecisionRequired": []
}
```

### 舵輪 3：Application CRUD、revision 與 API v2

執行契約（2026-07-23）：

- 唯一目標：交付 actor-scoped Calendar plan/activity/node application CRUD、revision、
  raw-key-free idempotency、category／primary HTTPS online link，以及獨立
  `/api/v2/calendar` REST surface。
- non-goals：不新增 Intent／LINE、sharing positive access、adoption、reminder worker、route、
  recurrence、ICS、attachment、Task／Knowledge binding或 legacy route。
- 候選 production：`calendar/application`、`api/calendar`、既有 `calendar/persistence`
  的必要擴充；候選 tests：`CalendarApplicationServiceTest`、`CalendarIntentApiTest` 之前的
  `CalendarApiTest`、既有 `CalendarPersistenceIntegrationTest`／`CalendarRlsIntegrationTest`
  regression；migration：coordinator 選定的
  `V67__add_calendar_crud_revision_and_online_link.sql`。
- resource claims：source operation `87ec9aa6-c52b-41c0-81cf-f1af9211b74e` 與
  `repo/flyway-sequence:main/V67` operation `040c4640-4b61-419d-b7e5-5141988f3c73`
  均為 `READY` 並正常釋放；Maven／Docker 仍由 repository wrapper 與 Testcontainers 管理。
- validation：先寫 application/API red tests；驗證 aggregate transaction rollback、相同 key
  replay一筆、不同 payload conflict、locked ordinary move拒絕、revision成功、child depth、
  HTTPS normalization／cardinality、category normalization、actor/RLS 與 success/failure response
  information leak；再跑 Calendar wheel 1–3 與 legacy baseline。

候選範圍：

- `calendar/application`、`api/calendar`。

Gate：

- plan/activity/node transaction 原子性。
- duplicate request 恰一 mutation。
- locked node 一般 edit 被拒；明確 revision 成功。
- child 無法再有 child。
- online link 只接受 normalized HTTPS、每 target 最多一筆 active primary link；category label
  normalization 與 revision 成立。
- API success／failure 都不洩漏內部資訊。

#### 舵輪 3 實際 gate evidence（2026-07-23）

- red test：先新增 `CalendarApplicationServiceTest` 與 `CalendarApiTest`；test compile 因
  `calendar.application` package 尚不存在而 exit code 1，確認測試不是在既有 production
  implementation 上假綠。
- coordinator 以實際最高 migration V66 預約 V67；source operation
  `87ec9aa6-c52b-41c0-81cf-f1af9211b74e` 與 Flyway operation
  `040c4640-4b61-419d-b7e5-5141988f3c73` 均為 `READY` 並正常釋放。
- `V67__add_calendar_crud_revision_and_online_link.sql` 新增 hashed creation request／payload、
  category、node semantic revision 與 actor-private `calendar_online_access_link`；primary link
  的 plan／activity／node target cardinality、composite ownership、HTTPS DB check 與
  `ENABLE/FORCE RLS` 都由 PostgreSQL constraint／policy 保護。V65→V66→V67 migration 與空 DB
  full migration 都通過，`schedule_item` fingerprint 未變。
- 建立 application service 在 mutation 前先驗證完整 plan/activity/node graph；nested semantic
  error 與後段 child unique error 都使 plan/activity/node/link 全部回滾。相同 request key 以
  SHA-256 保存、raw key 不落庫；不同 payload 回 `IDEMPOTENCY_CONFLICT`。建立改由 PostgreSQL
  `INSERT ... ON CONFLICT DO NOTHING` 原子仲裁，兩個真正同時到達的 caller 取得同一 public
  view，資料庫只有 1 plan／1 activity／2 nodes／1 link。
- node 一般 move 對 locked node fail closed，只有顯式 revision 且 revision token 正確時才
  更新；stale revision 拒絕。activity 沒有 child API 或 child collection，因此無法建立第二層
  child。
- category 經 NFKC／whitespace normalization 並參與 optimistic revision；online link 只接受
  normalized HTTPS，拒絕 user-info、fragment、非 HTTPS 與第二筆同 target primary link。
  invalid link 零 mutation。
- `/api/v2/calendar` 提供 plan 建立、以不透明 `Plan-Key` 查詢、category／online-link 更新，
  以及以業務 `nodeKey` 執行 ordinary move／explicit revision。success 與 failure regression
  確認不含 UUID、workspace、actor、entity／table name 或原始危險 URI；REST 沒有暴露 DB id。
  destructive cancel/archive 與完整生命週期不在本輪擅自加入，保留給後續狀態機輪次。
- 中途聚焦驗證首次在 production compile 攔到全天 placement accessor 命名錯誤，尚未啟動
  測試；修正 `start()`／`endExclusive()` 後，同一聚焦命令最終 8 tests、0 failures、
  0 errors、0 skipped。
- 最終 Calendar wheel 1–3 command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest,CalendarMigrationTest,CalendarPersistenceIntegrationTest,CalendarRlsIntegrationTest,CalendarApplicationServiceTest,CalendarApiTest" test`
  ；exit code 0；24 tests、0 failures、0 errors、0 skipped；耗時 84.7 秒。
- 最終 legacy／隔離 regression command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test`
  ；exit code 0；24 tests、0 failures、0 errors、0 skipped；耗時 75.1 秒。
- low-complexity REST create 以同一 warmed ApplicationContext 取 10 筆 MockMvc samples，
  P95 gate `<= 1500 ms` 通過；這不是 LINE／live provider latency，後兩者仍不得推定。
- 未測／剩餘：positive sharing authorization、reminder worker、adoption／route、Intent／LINE、
  recurrence、ICS、attachment、Task／Knowledge binding、live provider 與 cutover 均屬後續舵輪。

舵輪 3 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "3",
  "status": "PASS",
  "approvedDecisions": ["D01", "D02", "D03", "D04", "D09", "D10", "D11", "D12", "D13"],
  "modifiedFiles": [
    "src/main/resources/db/migration/V67__add_calendar_crud_revision_and_online_link.sql",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/application/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/persistence/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/api/calendar/",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/api/calendar/",
    "docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md"
  ],
  "migrationReservations": ["main/V67: READY, released"],
  "tests": [
    {"command": "mvn-safe Calendar wheel 1-3", "passed": 24, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-0 legacy/isolation baseline", "passed": 24, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main/V67 Flyway reservation", "main Maven target"],
  "remainingWork": ["wheel 4 calendar-owned reminders, occurrence rematerialization and reliable delivery"],
  "nextAction": "load wheel-4 reminder and notification-outbox dependencies, then reserve its migration before red tests",
  "risks": ["positive sharing remains intentionally unavailable", "Docker Desktop remains a shared retained service"],
  "userDecisionRequired": []
}
```

### 舵輪 4：多提醒與可靠投遞

執行契約（2026-07-23）：

- 唯一目標：建立 actor-owned Calendar reminder rule、有限 occurrence materialization、
  node revision rematerialization，以及透過既有 notification outbox 的可靠／冪等投遞。
- non-goals：不改舊 Task reminder schema／預設頻率，不新增 Intent／LINE、sharing、adoption、
  route、recurrence、ICS 或 Travel 3B。
- 候選 production：`calendar/reminder`、既有 `calendar/application`／`calendar/persistence` 的
  revision hook、`integration/notification` 的 channel allowlist 擴充、通用 LifeRecord recorder；
  候選 tests：Calendar reminder application／worker／migration／RLS 與既有 outbox regression；
  migration：`V68__create_calendar_reminder_rule_and_occurrence.sql`。
- resource claims：source operation `5e6fc9c4-3c48-4910-b4a8-527e956a8563` 與
  `repo/flyway-sequence:main/V68` operation `38638cd6-23ea-4b82-94db-618c3d7d580c`
  均為 `READY` 並正常釋放；Maven／Docker 仍由 repository wrapper 與 Testcontainers 管理。
- validation：先寫 rule quota／offset／revision／ACK／quiet-hours／outbox retry red tests，再驗證
  migration、RLS、transaction rollback、LifeRecord、Calendar wheel 1–4 與 legacy baseline。

候選範圍：

- calendar-owned reminder rule／occurrence。
- notification outbox adapter；必要時新增獨立 queue member kind，不改舊 Task member 語意。

Gate：

- 同節點多個 offset 精確排程。
- 第 8 個 active personal rule／shared template 可建立，第 9 個安全拒絕且零部分 mutation。
- reminder offset 大於 0 安全拒絕並引導建立 Task。
- node revision 對 relative／absolute reminder 的行為符合拍板規則。
- duplicate webhook／worker retry／restart 不重複投遞。
- old occurrence cancel 與 new occurrence materialization 原子、可稽核。
- 勿擾、channel preference、一次 alert／需 ACK 模式符合使用者決策。

#### 舵輪 4 實際 gate evidence（2026-07-24）

- red test：先新增 `CalendarReminderApplicationServiceTest` 與
  `CalendarReminderWorkerTest`。第一次命令先被 Spotless 攔截；只執行格式化後以相同 test
  selection 重跑，test compile 因 `CalendarReminderApplicationService`、
  `CalendarReminderOccurrenceWorker` 與 reminder enums 尚不存在而 exit code 1。
- coordinator 從實際最高 V67 預約 V68；source operation
  `5e6fc9c4-3c48-4910-b4a8-527e956a8563` 與 Flyway operation
  `38638cd6-23ea-4b82-94db-618c3d7d580c` 均為 `READY` 並正常釋放。
- `V68__create_calendar_reminder_rule_and_occurrence.sql` 新增 actor-private
  `calendar_reminder_rule`／`calendar_reminder_occurrence`、composite ownership FK、
  rule／occurrence identity、quota-supporting index、`ENABLE/FORCE RLS`，並為既有
  `calendar_time_node` forward-only 新增 `resolved_time`。V67→V68 fixture 實際 backfill
  absolute、owner-relative、node-relative 為 12:00／11:50／11:45；空 DB full migration
  亦通過，`schedule_item` fingerprint 未變。
- PERSONAL 與 SHARED_TEMPLATE 各自最多 8 個 active rules；兩者 quota 分開，template 不會直接
  materialize 私人 occurrence。第 8 筆成功，第 9 筆在持有 node pessimistic lock 下安全拒絕，
  exact duplicate semantic 也拒絕且沒有額外 rule／occurrence。
- relative offset 與 absolute fire time 都只能在 node 當下或之前；正 offset／node 後 absolute
  time 回 `CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK`，引導建立 Task。這不修改 Task deadline、
  placement 或舊 Task reminder。
- node revision 與 reminder rematerialization 在同一 transaction：舊 relative／absolute
  occurrences 都標記 `CANCELED`；relative 保留 offset，依新 node revision 建立一筆新
  `PENDING` occurrence；absolute rule 轉 `REVIEW_REQUIRED` 且不偷偷建立新時間提醒。
  node revision 與 reminder create/cancel 同步寫入通用 LifeRecord／tag graph recorder。
- 一般 reminder 預設／明示為 `ONCE`；CRITICAL 若未明確選擇是否 ACK 則拒絕。`ACK_REQUIRED`
  必須明示 interval 與 2–20 次上限，escalation occurrence 不占 rule quota；ACK 後原子取消
  尚未投遞的後續 occurrence。
- worker 只 claim actor-scoped due rows，使用 occurrence identity 形成穩定 delivery key，
  與既有 notification outbox 的 `(workspace, delivery_key, channel)` 唯一契約共同保證重跑／
  restart 不產生第二筆可見投遞。explicit channel allowlist 在 destination lookup 前過濾；
  勿擾／mute 會把同一 occurrence 延後而非丟棄。既有 outbox claim、lease recovery、retry、
  fenced acknowledgement 與 terminal payload erasure regression 均通過。
- runtime-role RLS evidence 擴大為 plan/activity/node/rule/occurrence 1/1/1/1/1；同 workspace
  peer、跨 workspace actor 與 SYSTEM 都為 0。正向 sharing 仍未開放，workspace membership
  不會推導私人 reminder access。
- 首輪 production 聚焦命令跨桌面暫停／喚醒後才產生 4/4 綠燈報告，wall time 約 11 小時；
  當時核對的 Maven／Java PID 已自然結束，沒有實際終止程序。此異常不作效能證據。後續清醒狀態
  聚焦曾有一筆 RLS fixture `Instant` JDBC 型別錯誤，以及兩筆 PostgreSQL 微秒／Java 奈秒
  assertion 差異；production 行為未失敗，分別改用明確 `Timestamp` 與微秒精度 expectation。
- 最終聚焦 command（Calendar reminder／migration／RLS＋既有 notification outbox）：
  15 tests、0 failures、0 errors、0 skipped；耗時 145.2 秒。
- 最終 Calendar wheel 1–4 command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest,CalendarMigrationTest,CalendarPersistenceIntegrationTest,CalendarRlsIntegrationTest,CalendarApplicationServiceTest,CalendarApiTest,CalendarReminderApplicationServiceTest,CalendarReminderWorkerTest" test`
  ；exit code 0；29 tests、0 failures、0 errors、0 skipped；耗時 69.6 秒。
- 最終 legacy／Task reminder regression command：
  `powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest,ReminderPreferenceServiceTest,ReminderTriggerServiceTest,ReminderEscalationServiceTest" test`
  ；exit code 0；39 tests、0 failures、0 errors、0 skipped；耗時 99.1 秒。
- 未測／剩餘：shared template adoption、positive sharing、route/adoption、Intent／LINE、recurrence、
  ICS、live provider 與 multi-actor shared-workspace preference authoring 屬後續舵輪；目前沿用既有
  reminder preference 查詢，不宣稱已完成跨 actor preference migration。

舵輪 4 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "4",
  "status": "PASS",
  "approvedDecisions": ["D07", "D08", "D18", "D19"],
  "modifiedFiles": [
    "src/main/resources/db/migration/V68__create_calendar_reminder_rule_and_occurrence.sql",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/reminder/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/application/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/calendar/persistence/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/integration/notification/",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/reminder/application/ReminderPreferenceService.java",
    "src/main/java/com/aproject/aidriven/mymobilesecretary/knowledge/tag/application/UniversalDomainEventRecorder.java",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/calendar/",
    "src/test/java/com/aproject/aidriven/mymobilesecretary/integration/notification/",
    "docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md"
  ],
  "migrationReservations": ["main/V68: READY, released"],
  "tests": [
    {"command": "mvn-safe Calendar wheel 1-4", "passed": 29, "failed": 0, "skipped": 0},
    {"command": "mvn-safe legacy plus Task reminder regression", "passed": 39, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main/V68 Flyway reservation", "main Maven target"],
  "remainingWork": ["wheel 5 personal adoption and route assessment"],
  "nextAction": "load wheel-5 adoption, planner constraint and route dependencies before red tests",
  "risks": ["positive sharing remains unavailable", "shared-workspace per-actor preference authoring remains deferred"],
  "userDecisionRequired": []
}
```

### 舵輪 5：個人採用與路徑評估

執行契約（2026-07-24）：

- 唯一目標：建立 versioned selected-node adoption snapshot、actor-private route projection，
  並以 Java `TravelTimeEstimator` 對已採用節點產生 deterministic route assessment。
- non-goals：不開放 sharing positive access，不改 legacy `ScheduleItem`／`FeasibilityService`，
  不讓 LLM 判定距離或移動節點，不做 Intent／LINE、recurrence、registration 或 Travel 3B。
- 候選 production：`calendar/adoption`、node effective location 的必要 persistence/application
  擴充、既有 planner `TravelTimeEstimator` adapter；候選 tests：
  `CalendarAdoptionServiceTest`、`PersonalRouteAssessmentServiceTest`、migration／RLS regression；
  migration：`V69__create_calendar_adoption_and_node_location.sql`。
- resource claims：source operation `6c335453-5ee1-4652-ba53-6a9810b21a0e` 與
  `repo/flyway-sequence:main/V69` operation `a65aed0e-9673-43ca-8be2-90d85dc09328`
  均為 `READY` 並正常釋放。
- validation：先寫 adoption snapshot／zero projection／same-place overlap／reachable／impossible／
  windowed alternative red tests，再驗證 actor/RLS、Calendar wheel 1–5 與 legacy baseline。

候選範圍：

- adoption aggregate。
- planner typed constraint adapter 與 `PersonalRouteAssessmentService`。

Gate：

- 未採用資料零個人 constraint。
- 同時段多活動建立成功。
- 同地 overlap、異地可達、異地不可達、windowed alternative 都有確定結果。
- route `IMPOSSIBLE` 不被 LLM／API 說成成功自動規劃。
- 手動保留風險不刪資料、不偷偷移動 flexible node。

#### 舵輪 5 實際 gate evidence（2026-07-24）

- red test：`CalendarAdoptionServiceTest`／`PersonalRouteAssessmentServiceTest` 格式化後，
  test compile 因 adoption／route production types 尚不存在而 exit code 1。
- source operation `6c335453-5ee1-4652-ba53-6a9810b21a0e` 與 main/V69 operation
  `a65aed0e-9673-43ca-8be2-90d85dc09328` 均 `READY` 並正常釋放。
- V69 新增 node effective location 與 actor-private `calendar_adoption`／
  `calendar_adoption_node`；selected node revision 形成 versioned snapshot，新節點不會因同 plan
  自動加入。未採用時 projection 為空；membership／可見性仍不推導 adoption。
- route assessor 只接收 actor 已採用的 typed constraints，並呼叫 Java
  `TravelTimeEstimator`。同地同時為 FEASIBLE；異地依 travel duration 與 gap 得到
  FEASIBLE／IMPOSSIBLE；含 WINDOWED node 的不可達結果為 ALTERNATIVE_AVAILABLE，但不修改時間。
  缺地點回 INSUFFICIENT_EVIDENCE；LLM 與 legacy `ScheduleItem` 都不參與判定。
- node location revision 是獨立事件並接 LifeRecord，不觸發時間 reminder rematerialization。
  adoption 建立也接通用 LifeRecord recorder。
- migration test 一度因 V69 assertion 錯放在 fixture 前而使 V68 backfill 未執行；修正為
  V67→fixture→V68→V69 後通過。V68 對既有 FORCE-RLS node 的 bounded backfill 在同一
  transactional migration 暫停 RLS，完成後立即恢復 ENABLE＋FORCE；absolute／owner-relative／
  node-relative 實測為 12:00／11:50／11:45。V69 空 DB／existing DB、兩表 RLS 與 legacy
  `schedule_item` fingerprint 均通過。
- 聚焦 adoption／route／migration／RLS：6 tests、0 failures、0 errors、0 skipped；97.2 秒。
- Calendar wheel 1–5 最終 command：12 個 test classes；32 tests、0 failures、0 errors、
  0 skipped；123.2 秒。前一輪僅一個 PostgreSQL 微秒 rounding assertion 差 1µs，改成明確
  ±1µs DB precision 後全綠，未改 production 計算。
- legacy／Task reminder／feasibility regression：54 tests、0 failures、0 errors、0 skipped；
  86.6 秒。
- 未測／剩餘：sharing positive access、participation／skip impact preview、Intent／LINE query
  view、live route provider、recurrence 與跨 workspace merge。Travel 3B 仍未啟動。

舵輪 5 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "5",
  "status": "PASS",
  "approvedDecisions": ["D03", "D05", "D06"],
  "migrationReservations": ["main/V69: READY, released"],
  "tests": [
    {"command": "mvn-safe Calendar wheel 1-5", "passed": 32, "failed": 0, "skipped": 0},
    {"command": "mvn-safe legacy reminder and feasibility regression", "passed": 54, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main/V69 Flyway reservation", "main Maven target"],
  "remainingWork": ["wheel 6 query views, natural-language entry and cutover routing"],
  "nextAction": "load wheel-6 query, intent, capability catalog and LINE formatter dependencies",
  "risks": ["positive sharing and live route provider remain deferred"],
  "userDecisionRequired": []
}
```

### 舵輪 6：Query view 與自然語言入口

候選範圍：

- day/week/month/list/search query model。
- intent handler、capability catalog、LINE／API response formatter。

Gate：

- cutover 前 v2／legacy 入口不誤路由、不合併、不 dual-write；cutover 後普通入口只進新版。
- 完整、口語、省略、修正、引用、鄰近 Intent 與 holdout 通過。
- ambiguous target 回問且零 mutation。
- keyword＋typed filters、逐 workspace authorized bounded merge、ranking／pagination deterministic；
  private roster／Knowledge／reminder／route／location／attachment 零 search leakage。
- low／medium latency 與 progress contract 通過。

實作結果（2026-07-24）：

- 新增 actor-scoped `CalendarQueryService` 與不含內部 ID 的 query item/page/filter。
  day／week／month 共用半開 range；timed interval 以 overlap、timed point 以 containment、
  all-day 以台北 local-date overlap 查詢。keyword＋category 皆 bounded，搜尋依
  exact／prefix／contains、全天優先、有效日期時間、標題與內部 tie-breaker 穩定排序，
  以 limit+1 產生 deterministic next offset。
- query SQL 只讀 `calendar_plan` 與 plan primary online link 的 `safe_host`；不 join
  private roster、Knowledge、reminder、route、location、attachment 或 legacy
  `schedule_item`。workspace／actor 同時由 Java predicate 與既有 FORCE RLS 保護。
- 新增 `app.calendar-v2.cutover-enabled` 與 `pilot-actor-ids` 路由。flag 關閉時保留 legacy；
  開啟或命中 pilot actor 時，`CREATE_SCHEDULE`、`LIST_SCHEDULES`、
  `LIST_SCHEDULES_ON_DATE`、`ASK_SCHEDULE_INFO` 只走 v2，沒有 merge 或 dual-write。
  自然語句每日總覽的 deterministic shortcut 也使用相同 routing service，不能繞過 cutover。
- create 的 application idempotency key 使用既有 request correlation；同 request replay
  由 Calendar v2 creation arbitration 回傳同一筆。缺結束時間、partial range、多候選或
  找不到唯一目標時只回問且零 mutation。
- LINE／API 沿用 `IntentResult` formatter，只輸出標題、typed placement、分類與安全 host；
  沒有 calendar／workspace／actor UUID、完整 URL 或其他私人表欄位。
- capability catalog 由 404 擴為 420，新增 16 個完整、口語、省略、分類、日／週／月、
  keyword、更正、引用、ambiguous、鄰近 destructive 與 privacy holdout 案例；沒有新增
  Intent Type。
- Calendar query integration：3 tests 通過，包含三種 placement、重疊、ranking、分頁、
  actor boundary、不讀 legacy，以及 20 次 warm query P95 固定 gate ≤1.5 秒。結果集上限
  20，屬 low-latency contract，不需要 progress message。
- Calendar wheels 1–6 最終 gate：19 test classes、56 tests、0 failures、0 errors、
  0 skipped；335.0 秒。
- legacy Intent／daily schedule／reminder／feasibility／notification regression：
  94 tests、0 failures、0 errors、0 skipped；119.3 秒。
- 未測／剩餘：positive sharing、participation／skip impact preview、live route provider、
  recurrence、跨 workspace authorized merge 與正式 hard cutover／legacy removal。
  Travel 3B 仍等待 Project typed binding gate，未因本輪自動恢復。

舵輪 6 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "6",
  "status": "PASS",
  "approvedDecisions": ["D01", "D02", "D03", "D04", "D05", "D06", "D07", "D11", "D12", "D13", "D14", "D57", "D58", "D59", "D60", "D61"],
  "migrationReservations": [],
  "tests": [
    {"command": "mvn-safe Calendar wheels 1-6", "passed": 56, "failed": 0, "skipped": 0},
    {"command": "mvn-safe legacy intent/reminder/feasibility regression", "passed": 94, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main Maven target"],
  "remainingWork": ["wheel 7 Task, Planning and Planner convergence"],
  "nextAction": "load wheel-7 planning, task binding, planner and migration dependencies",
  "risks": ["positive sharing, recurrence and live route provider remain deferred", "hard cutover and legacy removal require later destructive gate"],
  "userDecisionRequired": []
}
```

### 舵輪 7：Task／Planning／Planner 收斂

候選範圍：

- `planning` type／classifier／transition policy。
- typed CalendarTaskBinding。
- Task deadline／placement／reminder contract。
- FlexibleDayTaskPlan、DeferredTask 與 planner/free-slot adapter。

Gate：

- 使用者可感知類型只保留 Draft、Task、Calendar、Knowledge；舊 `SCHEDULE_REMINDER` 不再出現在
  新入口、回覆或 capability contract。
- 同一語句依「要完成的工作／客觀日曆節點／單純通知」正確建立 Task、CalendarTimeNode 或 reminder
  rule；缺少唯一語意時回問且零 mutation。
- Task deadline、calendar placement、reminder rule 可獨立建立／修改／取消，任一操作不靜默移動另外兩者。
- 明確的節點後追蹤需求建立 linked Task，不建立正 offset CalendarReminderRule。
- 完成／取消 Task 不刪 Calendar 歷史；取消 Calendar 不冒充 Task 完成。
- CalendarTaskBinding 使用真實 FK、actor/workspace RLS、idempotency 與 exactly-one target constraint。
- Feasibility／FreeSlot 與 flexible-day planning 改讀 PersonalRouteProjection；新版路徑不讀
  ScheduleItemRepository，且只使用本人已採用 constraints。
- Planning、Task、Planner、Intent、API 與 conversation regression 全通過。

實作結果（2026-07-24）：

- `PlanningItemType` 收斂為 `DRAFT`、`TASK`、`CALENDAR`、`KNOWLEDGE`；Task 可有 deadline
  與獨立 reminder，但不因此改成 Calendar。舊 `SCHEDULE_REMINDER` 僅保留相容 Intent 名稱，
  新回覆與 capability 語意不再把它顯示為使用者可感知類型。
- V70 新增 actor/workspace scoped `calendar_task_binding`，以真實 composite FK 綁定
  plan／activity／node，具 exactly-one target constraint、request/payload hash idempotency、
  一 Task 一 target 與 FORCE RLS。Task 完成／取消不刪 Calendar plan、node 或 binding。
- V71 新增 `task_reminder_rule`；Task deadline 與提醒可獨立建立、改期、取消。Calendar v2
  路徑的非週期 Task deadline 不再隱式排入提醒 queue；週期 Task 保留既有排程語意。
- 明確「Calendar 節點後」語句由 Java 以 actor-scoped 唯一 plan/node 解析，建立 linked Task，
  deadline 為 resolved node time 加受限 delay；重送維持同一 Task/binding，且不建立正 offset
  `calendar_reminder_rule` 或隱式 `task_reminder_rule`。
- 新增 `PersonalRouteProjection`，只投影本人 `ACTIVE` adoption 的 busy interval 與 route
  constraint。Calendar v2 的 `FreeSlotService`／`FeasibilityService` 只讀該 projection，
  不讀 `ScheduleItemRepository`；重疊仍可保存，路線評估只提供建議。
- Flexible-day Task 的目標日落為 deadline，選定提醒另存獨立 Task reminder rule；
  `CalendarTaskBoundEvent` 已接入通用 LifeRecord recorder。
- capability catalog 由 420 擴為 421，加入節點後 linked Task 案例；API regression 驗證
  新增／取消提醒不改 deadline、不建立 Calendar，以及同 request replay 不重複 materialize。
- 精準 integration/regression：16 tests、0 failures、0 errors、0 skipped。
- Wheel 7 完整功能 gate：16 test classes、51 tests、0 failures、0 errors、0 skipped；
  343.7 秒。
- legacy Intent／Task／reminder／schedule insight／feasibility／notification regression：
  101 tests、0 failures、0 errors、0 skipped；130.6 秒。
- 未測／剩餘：Knowledge typed binding/excerpt/attachment grant、positive sharing、recurrence、
  live route provider、正式 hard cutover 與 legacy removal。Travel 3B 仍等待 Project typed
  binding gate，未因 CalendarTaskBinding 自動恢復。

舵輪 7 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "7",
  "status": "PASS",
  "migrations": ["V70__create_calendar_task_binding.sql", "V71__create_task_reminder_rule.sql"],
  "tests": [
    {"command": "mvn-safe wheel-7 precise integration/regression", "passed": 16, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-7 complete functional gate", "passed": 51, "failed": 0, "skipped": 0},
    {"command": "mvn-safe legacy task/reminder/schedule regression", "passed": 101, "failed": 0, "skipped": 0}
  ],
  "claimsReleased": ["worktree source", "main Maven target"],
  "remainingWork": ["wheel 8 Knowledge and Calendar typed integration"],
  "nextAction": "load wheel-8 Knowledge binding, StoredMedia grant and LifeRecord dependencies",
  "risks": ["Knowledge sharing and attachment grants must remain separate from Calendar visibility", "hard cutover and legacy removal require later destructive gate"],
  "userDecisionRequired": []
}
```

### 舵輪 8：Knowledge × Calendar typed integration

候選範圍：

- CalendarKnowledgeAnnotationBinding／CalendarKnowledgeFactBinding。
- CalendarKnowledgeExcerpt 與 share authorization。
- CalendarAttachmentBinding、StoredMedia 與逐 asset content grant。
- bounded knowledge retrieval、typed materialization 與 LifeRecord recorder。

Gate：

- plan／activity／node 與 Knowledge 使用真實 FK；不得新增通用 `CALENDAR + targetId` binding。
- 分享計畫不會自動分享私人 Knowledge、tag graph、附件或其他 facts；只有本人批准的版本化 excerpt
  對授權 recipient 可見。
- Knowledge 文字、附件或 retrieval evidence 永遠不能直接觸發 calendar/task/reminder mutation。
- Knowledge materialize 成 Task、TimeNode、PlanningPreference、BufferRule 或 reminder rule 時，Java
  驗證 typed command、明確 consent、actor/workspace 與 idempotency。
- Knowledge 更新使既有 binding／excerpt 進 review，不靜默改變 route；不能繞過 D14 authoritative change。
- attachment binding 不分享 bytes；explicit grant／revoke、unlink、replace 與 media delete lifecycle
  分離，cross-actor/RLS/download header/unsupported type/size quota 全通過。
- Calendar／Knowledge 任一方取消或封存不 cascade delete 另一方。
- Calendar domain event 進 LifeRecord／tag graph；純檢視、查詢與 binding 不重複建立生活事件。
- 成功／失敗／引用／鄰近 Intent、RLS、資訊洩漏與 holdout 全通過。

實作進度（2026-07-25，PASS）：

- V72–V82 已建立 Knowledge fact／annotation typed binding、versioned excerpt、
  attachment binding、whole-plan VIEWER share、逐 excerpt／asset grant、materialization proposal
  snapshot、lifecycle review／revoke、durable outbox、ProjectCalendarPlanBinding 與 LifeRecord
  exactly-once recorder；V82 將 attachment replacement 固定為 immutable replacement chain，
  舊 binding 進 `REPLACED`、新 binding 為 `ACTIVE`，replacement ledger append-only，歷史
  idempotency replay 不新增 mutation。owner／recipient application filter 與 FORCE RLS 已通過
  整合 gate。
- `ASK_CALENDAR_KNOWLEDGE` 與 `BIND_KNOWLEDGE_TO_CALENDAR` 已走實際 `/api/intent`：
  actor-scoped exact source／plan／activity／node resolution，knowledge evidence 中的取消或提醒文字
  只讀顯示，不成為命令；binding replay 只留一筆。
- `MATERIALIZE_CALENDAR_KNOWLEDGE` 第一版只對話開放 Task／CalendarTimeNode，採
  PROPOSE → 明確 CONFIRM／CANCEL；typed snapshot 支援全部六種 domain command，Java 驗證
  source revision、command hash、server conversation scope 與 idempotency。實際 API gate 證明
  proposal 時 Task=0、確認後 Task=1、確認 replay 仍 Task=1。
- LINE proposal quote 使用 tenant-scoped server metadata 與 request-local trusted reference；
  proposal UUID 不進 LLM context。無 quote 只接受同 scope 唯一 pending；多筆時回問且零 mutation。
- C140 已走實際 LINE webhook：PDF 上傳取得可信 media reference；引用後只建立 exact node
  attachment binding，core share 與逐 asset content grant 均為 0，回覆分開預覽兩項授權。
  C141–C144 binding／grant／revoke／unlink／RLS／shared content API 合併驗收 41/41。
- C145 已新增 `CHANGE_CALENDAR_CATEGORY`：Java exact plan resolution 與 revision lock 只更新
  normalized category；color 明確回覆尚未交付，criticality／permission／participation 不變，
  精準 gate 6/6。
- 本輪主要批次：migration／share／RLS 10/10；actual API／LINE／materialization／catalog
  73/73；LifeRecord／latency 10/10；transaction failure handoff 23/23；C143 cross-workspace
  leak seed、replacement RLS／append-only 15/15；neighbor route 11/11。capability catalog
  已擴為 439。
- 本輪定位並修正的 P1 包含：expected business failure 在 focus transaction 內安全 rollback
  並由外層回覆、C143 同 workspace peer／跨 workspace media 維持零 binding／grant／outbox／
  media mutation 且不洩漏 metadata／internal id、否定行程語句不再覆蓋 Task intent，以及
  attachment replacement 的撤銷、outbox 與 append-only／RLS 不變量。
- opt-in latency gate 全通過：direct warm P95 8ms、actual API warm P95 149ms、
  application-cold P95 225ms、actual quoted LINE P95 459ms。
- sealed holdout 使用固定 input SHA
  `c917d340e65f91cbdd3f07f5bbc0c0273ff43ee2bdde04bbd7b67378c267c37a`，共 16 cases／
  19 turns；capture SHA `32a65473cc6c0e397cd9d964944094bff41d079cd3bc80e11c6c9e9b80477ab3`、
  oracle SHA `c09e67e05d6a730378164539e904d107fb69b377e9da28c9062965ec41869aed`，
  UX 最低分 clarity／actionability／expectation／safety／consistency 為 4／4／5／5／5，
  capture＋assert 合計 96.1 秒。
- root regression 最終 1417 tests、0 failure、0 error、18 skipped，耗時 265.9 秒；Wheel 8
  gate 已完成，可進入 Wheel 9。Wheel 9 的 selected-scope sharing、EDITOR 與 participation
  仍未開始，不由本 handoff 宣稱完成。

舵輪 8 handoff：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "8",
  "status": "PASS",
  "completedAt": "2026-07-25",
  "migrations": [
    "V72__create_calendar_knowledge_bindings.sql",
    "V73__create_calendar_attachment_binding.sql",
    "V74__create_calendar_knowledge_excerpt.sql",
    "V75__create_knowledge_materialization.sql",
    "V76__add_calendar_plan_lifecycle.sql",
    "V77__create_project_calendar_plan_binding.sql",
    "V78__create_calendar_share_content_grant.sql",
    "V79__authorize_shared_calendar_attachment_binding.sql",
    "V80__enforce_calendar_share_content_lifecycle.sql",
    "V81__add_life_record_event_identity.sql",
    "V82__version_calendar_attachment_replacement.sql"
  ],
  "tests": [
    {"command": "mvn-safe wheel-8 migration/share/RLS precision", "passed": 10, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-8 actual API/LINE/materialization/catalog", "passed": 73, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-8 LifeRecord/latency", "passed": 10, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-8 transaction failure precision", "passed": 23, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-8 C143/RLS/append-only precision", "passed": 15, "failed": 0, "skipped": 0},
    {"command": "mvn-safe wheel-8 neighbor route precision", "passed": 11, "failed": 0, "skipped": 0},
    {"command": "sealed holdout capture/assert", "cases": 16, "turns": 19, "failed": 0, "durationSeconds": 96.1},
    {"command": "mvn-safe root regression", "passed": 1399, "failed": 0, "errors": 0, "skipped": 18, "total": 1417, "durationSeconds": 265.9}
  ],
  "latencyP95Milliseconds": {
    "directWarm": 8,
    "actualApiWarm": 149,
    "actualApiApplicationCold": 225,
    "actualQuotedLine": 459
  },
  "sealedHoldout": {
    "inputSha256": "c917d340e65f91cbdd3f07f5bbc0c0273ff43ee2bdde04bbd7b67378c267c37a",
    "captureSha256": "32a65473cc6c0e397cd9d964944094bff41d079cd3bc80e11c6c9e9b80477ab3",
    "oracleSha256": "c09e67e05d6a730378164539e904d107fb69b377e9da28c9062965ec41869aed",
    "uxMinimum": {"clarity": 4, "actionability": 4, "expectation": 5, "safety": 5, "consistency": 5}
  },
  "downstreamHandoffs": {
    "travel3BA_calendarTypedIntegration": "READY"
  },
  "claimsReleased": ["Calendar Wheel 8 source scope", "main Flyway V72-V82 reservations", "main Maven target"],
  "remainingWork": ["Wheel 9 selected-scope sharing, EDITOR and participation", "Wheels 10-12"],
  "nextAction": "start Wheel 9 retrospective and load only sharing/participation dependencies",
  "risks": ["Different request keys for semantically duplicate grants can still create duplicate outbox rows", "Wheel 9 authorization expansion must preserve Wheel 8 content-grant separation"],
  "userDecisionRequired": []
}
```

### 舵輪 9：多群組共享、參加／略過與變更 review

前提：group workspace／membership 與跨群組 read contract 已驗證；D21–D31、D40–D48 已拍板。

Gate：

- 一個 actor 可加入多個群組，並在「我的行事曆」看見來源清楚的授權活動；部分群組查詢失敗不
  得冒充完整結果。
- PRIVATE／SELECTED_MEMBERS／GROUP_VISIBLE 與同-workspace active membership boundary。
- LIVE_WHOLE_PLAN／SELECTED_ACTIVITIES／SELECTED_NODES、selected snapshot 與 minimal dependency
  closure；新增 descendant 不誤入 selected scope。
- additive grant／effective permission、VIEWER／EDITOR 與 authoritative/participant-manager capability
  分離；group admin 不旁路 private owner。
- core／attachment／Knowledge excerpt／roster／private reminder 授權完全分離。
- grant/revoke/role change outbox、idempotency、personal snapshot retention、active participant last-access
  resolution 與 ownership transfer。
- membership、visibility、participation、watch、adoption、personal reminder 權限完全分離。
- viewer 不可 edit；editor 不可替別人採用或改私人提醒。
- optional 未參加活動零 route／reminder；已承諾或 REQUIRED 活動略過需 impact preview 與
  revision-bound confirmation。
- 略過只撤銷本人 projection 與 routine messages；不刪 shared source、他人資料、Task／Knowledge
  或歷史，且 non-suppressible system messages 仍可投遞。
- scoped participant manager 可看具名 roster 與必要統計；其他 group role／participant 查詢 fail
  closed，且 roster response／notification 零私人 reminder、Knowledge、location 與內部 identifier。
- registration deadline、capacity、late-join policy、hard/override limit 與 waitlist offer 狀態由 Java
  決定；最後一席並行加入不超賣。
- 截止後本人退出不可被否決；user withdrawal／organizer removal 分流、audit 與雙方必要通知正確。
- 候補 FIFO、手動調序理由、offer expiry、本人接受、超時遞補與 duplicate delivery 全部 deterministic。
- capacity 下修只產生可管理的 OVER_CAPACITY，不自動踢人；manager override 有警示、理由與稽核。
- 關鍵 node revision 使正確 adopters review，不通知未授權 actor。
- authoritative editor 僅能在授權 scope 變更時間／地點／取消狀態；COMMITTED 自動套用、
  TENTATIVE review、WATCHING 只依私人 subscription 收 routine update、DECLINED／OPTED_OUT
  不建立 constraint。
- 權威變更、舊 occurrence 取消、relative reminder 重算與 mandatory notification 原子且可重送；
  投遞失敗可見，不偽造已通知或本人已同意。
- 位置事件與未共享個人路徑不外洩。

實作進度（2026-07-25，IN_PROGRESS／W9-A PASS）：

- V83 已分離 adoption 的 source owner 與 adopter actor；whole-plan VIEWER 只新增
  activity／node core SELECT，不取得 UPDATE、私人 reminder、Knowledge、attachment 或 roster。
- visibility／membership／share 本身維持零 route／busy；recipient 明確採用 selected node 後，
  只有本人的 versioned projection 生效，owner／peer／admin 不取得其私人 adoption。
- 正式 NOBYPASSRLS migration 的既有資料 backfill 採 transaction-bounded
  `NO FORCE／DISABLE`，完成後同 migration 恢復 `ENABLE／FORCE`；既有 adoption 補
  `ADOPTED` history。plan lifecycle 使用 actor-scoped source marker 與
  `security_invoker` effective state，不建立 BYPASSRLS function，也不讓 source owner讀取 recipient
  adoption。
- W9-A test-compile 通過；shared adoption 功能、NOBYPASSRLS、lifecycle、migration catalog 與既有
  adoption／route／plan lifecycle 精準 gate 17/17。此 handoff 只固定 V83 與 shared-adoption
  foundation；selected scope、EDITOR、participation／registration、ownership transfer 尚未完成。

```json
{
  "plan": "calendar-plan-v2",
  "phase": "9-A",
  "status": "PASS",
  "wheelStatus": "IN_PROGRESS",
  "migration": {
    "reserved": "V83",
    "file": "V83__allow_shared_calendar_adoption.sql",
    "actualLatestAfterGate": "V83"
  },
  "tests": {
    "testCompile": "PASS",
    "focused": {"tests": 17, "failures": 0, "errors": 0, "skipped": 0}
  },
  "scope": [
    "source-owner/adopter identity separation",
    "whole-plan VIEWER descendant SELECT-only",
    "recipient-private adoption and history",
    "actor-scoped source lifecycle effective state"
  ],
  "claimsReleased": [
    "worktree/source D:/my-project/my-mobile-secretary",
    "repo/flyway-sequence/main/V83",
    "main Maven target"
  ],
  "downstreamBookingB2": {
    "sourceAndFlywayReclaim": "ALLOWED_AFTER_COORDINATOR_RECHECK",
    "claimTransfer": "NONE"
  },
  "remainingWork": [
    "Wheel 9 selected scope and additive ACL",
    "EDITOR and scoped capabilities",
    "participation, registration, roster and waitlist",
    "ownership transfer and last-access resolution"
  ]
}
```

實作進度（2026-07-26，IN_PROGRESS／W9-B PASS）：

- V85 將 whole-plan 與 selected sharing 分成
  `LIVE_WHOLE_PLAN／SELECTED_ACTIVITIES／SELECTED_NODES`；selected scope 使用
  versioned sealed snapshot，新增 descendant 不會自動擴張。owner 必須以 targets、target
  revision 與 dependency projection 的 preview digest 明確 revise，才切換下一個
  `scope_revision`；role/lifecycle 的 `share_revision` 保持分離。
- ACL 採 active grants 聯集；semantic fingerprint 與 request receipt 分離，使不同 request key
  的同語意 grant（包含並行）只建立一份 share/snapshot/outbox。撤銷單一 selected grant 不影響
  whole-plan 或其他 selected grant。
- selected node 只取得 plan status、owner display name、最小 activity context、指定 node 的
  時間／位置／criticality／adjustability 與精準 online-link；siblings、attachment、Knowledge
  excerpt、route、reminder、roster 不因 core share 自動授權。
- relative node 的 scope 外 base 只保存 `dependentNodeId + resolvedTime + revision`，target 只公開
  allowlisted expression kind／offset；consumer 可 deterministic 重算但讀不到 base label、location
  或完整 node row。多層或無法安全揭露的 dependency 整筆 rollback。
- V85 既有 share backfill 在同 migration 內 transaction-bounded
  `NO FORCE／DISABLE`，完成後立即恢復 `ENABLE／FORCE`；snapshot/item/receipt 均 FORCE RLS，
  composite deferred FK 綁 mode/revision/ownership，sealed insert guard 使用 row lock，避免
  seal/append race。
- 最新 focused gate 44/44，0 failure／0 error／0 skipped；獨立 final security 與 test re-audit
  均無 blocker/high。此 handoff 固定 selected VIEWER/additive foundation；EDITOR、
  participation/skip、registration/roster/waitlist 與 ownership transfer 仍屬 W9-C–E。

```json
{
  "plan": "calendar-plan-v2",
  "phase": "9-B",
  "status": "PASS",
  "wheelStatus": "IN_PROGRESS",
  "migration": {
    "reserved": "V85",
    "file": "V85__create_calendar_selected_share_scope.sql",
    "actualLatestAfterGate": "V85"
  },
  "tests": {
    "focused": {"tests": 44, "failures": 0, "errors": 0, "skipped": 0},
    "newSelectedScope": {"tests": 20, "failures": 0, "errors": 0, "skipped": 0},
    "independentSecurityReaudit": "PASS_NO_BLOCKER_HIGH",
    "independentTestReaudit": "PASS_NO_BLOCKER_HIGH"
  },
  "scope": [
    "sealed versioned selected-activity and selected-node snapshots",
    "preview-digest-bound scope revision and explicit expansion",
    "additive grant union and partial revoke",
    "semantic idempotency receipts and one visible outbox transition",
    "minimal relative dependency projection and deterministic recomputation",
    "owner/recipient separated FORCE RLS and minimal selected query"
  ],
  "claimsReleased": [
    "worktree/source D:/my-project/my-mobile-secretary",
    "repo/flyway-sequence/main/V85",
    "main Maven target"
  ],
  "downstream": {
    "bookingB3": "MUST_REACQUIRE_SOURCE_AND_NEXT_FLYWAY",
    "travel3BB": "STILL_BLOCKED_BY_CALENDAR_WHEEL_10",
    "claimTransfer": "NONE"
  },
  "remainingWork": [
    "W9-C EDITOR and authoritative scoped mutations",
    "W9-D participation, skip and personal snapshot retention",
    "W9-E registration, roster, capacity and waitlist",
    "ownership transfer and last-access resolution",
    "Wheels 10-12"
  ],
  "nextAction": "start W9-C retrospective and red tests after independent source/Flyway recheck"
}
```

實作進度（2026-07-26，IN_PROGRESS／W9-C PASS）：

- V86 將一般 `EDITOR` 與 objective-field `AUTHORITATIVE_EDITOR` capability 分離；一般 editor
  只能在有效 whole-plan／selected frozen scope 內修改 node label 或 activity title/category，
  無法管理 share、adoption、私人 reminder、roster，亦不能修改時間、地點或取消狀態。
- authoritative capability 只允許 active EDITOR share 的 PLAN／NODE scope intersection；
  grant、explicit revoke、role downgrade 與 share revoke 都是 versioned、idempotent、逐 capability
  durable outbox，且 DB deferred invariant 拒絕無 outbox、越權 scope 或直接 DELETE。
- time／location／cancellation mutation 使用 request 與 semantic advisory lock、固定
  plan→share→capability→node 鎖序、append-only audit、精確 target transition 與 structured
  outbox FK/unique。相同或不同 request key replay 收斂，mutation 對 revoke/downgrade 的競態只會
  完整先後提交，不留下 partial audit、outbox、node 或 reminder state。
- canceled node 是 terminal source state：entity、selected tombstone、adoption/route、reminder 建立與
  reminder worker 都 fail closed；取消後不可再改時間／地點。權威改時會在同交易取消 PENDING／
  ENQUEUED 舊 occurrence、重建 owner 的 PERSONAL relative reminder、將 absolute rule 轉 review；
  權威取消會終止 active rule 與尚存 occurrence；location 不碰 reminder。
- editor actor 不可同步冒充 source owner 寫 LifeRecord。authoritative transaction 以結構化 mutation
  FK 原子建立 owner-owned durable handoff；owner-scoped worker 使用 stable mutation identity
  exactly-once 寫入 LifeRecord/tag graph，time/location/cancellation 均驗證 editor 零紀錄。
- final security/neighbor gate 96/96，0 failure／0 error／0 skipped；包含 sharing/RLS、
  attachment/Knowledge boundary、editor/capability/mutation、reminder、adoption/route、
  LifeRecord 與 architecture neighbor。獨立 final security/test re-audit 均為
  PASS、0 blocker／0 high。

```json
{
  "plan": "calendar-plan-v2",
  "phase": "9-C",
  "status": "PASS",
  "wheelStatus": "IN_PROGRESS",
  "migration": {
    "reserved": "V86",
    "file": "V86__create_calendar_editor_capability.sql",
    "actualLatestAfterGate": "V86"
  },
  "tests": {
    "testCompile": "PASS",
    "finalSecurityNeighbor": {
      "tests": 96,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "independentSecurityReaudit": "PASS_NO_BLOCKER_HIGH",
    "independentTestReaudit": "PASS_NO_BLOCKER_HIGH"
  },
  "scope": [
    "whole-plan and selected-scope general EDITOR mutations",
    "scoped authoritative time, location and cancellation",
    "versioned capability lifecycle and structured durable outbox",
    "atomic source-owner reminder consequences",
    "owner-scoped exactly-once LifeRecord handoff",
    "terminal cancellation and revoke/downgrade race safety"
  ],
  "claimsReleased": [
    "worktree/source D:/my-project/my-mobile-secretary",
    "repo/flyway-sequence/main/V86"
  ],
  "remainingWork": [
    "W9-D participation, skip, propagation and personal snapshot retention",
    "W9-E registration, roster, capacity, waitlist, ownership and last-access resolution",
    "Wheels 10-12"
  ],
  "nextAction": "start W9-D retrospective and acquire next source/Flyway claim after coordinator recheck"
}
```

實作進度（2026-07-27，IN_PROGRESS／W9-D PASS）：

- V87 將 visibility、participation、watch subscription、adoption、personal projection 與
  reminder ownership 分離；`COMMITTED`、`TENTATIVE`、`WATCHING`、`DECLINED`、`OPTED_OUT`
  各自產生明確 disposition，不由 membership 或 share 推導使用者同意。
- owner 與 authorized editor mutation 都先建立 typed `SOURCE_MUTATION` signal。一般 owner
  time／location／cancellation 進 `GENERAL_REVIEW`；authoritative editor 只有在 V86 audit/outbox
  完整時才進 `AUTHORITATIVE_AUTO_APPLY`。控制流程不解析自由文字 payload。
- personal projection 對 authoritative committed participant 自動套用，對 general owner 或
  tentative participant 建立 review-required snapshot；watching 只收 routine update，退出／拒絕
  不建立 personal mutation。取消 source node 會關閉 actor-owned reminder future work。
- Calendar participation outbox 與通用 notification outbox 使用 stable delivery key 收斂；
  provider 尚未完成時 receipt 保持 PENDING，generic SENT 才轉 SENT，DEAD_LETTER／無可投遞
  destination 轉 FAILED。重跑不重建 personal projection，`NOT_REQUIRED` 以明確終態保存。
- W9-D focused、RLS、delivery、security 與 neighbor gates 全綠。完整回歸曾揭露兩組既有
  forward-compatibility test debt：Booking B2 fixture 跨日過期／奈秒精度，以及 Wheel 8 lifecycle
  測試繞過 V86 actor transaction guard；只修 test oracle/fixture，不放寬 production 授權、
  V84 或 V86。
- `TR-DESKTOP-B3-START` 已在 W9-D root gate 前多次 fetch 查核；`origin/main` 仍為
  `99882c47d66a0018e3cc6b259de4efbd015d9c53`，不含 V84 或 laptop trigger state，
  因此狀態為 `BLOCKED_NEEDS_PUBLISH`，不得標 READY。

```json
{
  "plan": "calendar-plan-v2",
  "phase": "9-D",
  "status": "PASS",
  "wheelStatus": "IN_PROGRESS",
  "migration": {
    "reserved": "V87",
    "file": "V87__create_calendar_participation_and_personal_projection.sql",
    "actualLatestAfterGate": "V87"
  },
  "tests": {
    "participationFocused": {
      "tests": 27,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "notificationDelivery": {
      "tests": 2,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "databaseArchitecture": {
      "tests": 3,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "securityNeighbor": {
      "tests": 119,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "lifecycleNeighbor": {
      "tests": 18,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "bookingForwardCompatibility": {
      "tests": 8,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "rootRegression": {
      "tests": 1514,
      "failures": 0,
      "errors": 0,
      "skipped": 18,
      "durationSeconds": 304.2
    }
  },
  "externalEnvironment": "FAKE",
  "sideEffectState": "LOCAL_DB_ONLY_NO_EXTERNAL_MUTATION",
  "claimsReleased": [
    "worktree/source D:/my-project/my-mobile-secretary",
    "repo/flyway-sequence/main/V87",
    "machine/docker-capacity/testcontainers",
    "wrapper-owned Maven target"
  ],
  "crossLaneTrigger": {
    "eventId": "TR-DESKTOP-B3-START",
    "status": "BLOCKED_NEEDS_PUBLISH",
    "verifiedOriginMain": "99882c47d66a0018e3cc6b259de4efbd015d9c53"
  },
  "remainingWork": [
    "W9-E registration, roster, capacity, waitlist, ownership and last-access resolution",
    "Wheels 10-12"
  ],
  "nextAction": "release W9-D claims, recheck trigger, then start W9-E only when no HARD_YIELD is READY"
}
```

### 舵輪 10：Recurrence 與 ICS anti-corruption adapter

前提：核心 one-off flow 已穩定；D12、D32–D38、D49–D56 已拍板。

Gate：

- DAILY／WEEKLY／MONTHLY／YEARLY、interval、multi-weekday、nth/last weekday、last day、COUNT／
  inclusive UNTIL、exclude/add/override。
- THIS_OCCURRENCE／THIS_AND_FUTURE／ENTIRE_SERIES，split lineage 與 immutable history。
- plan/activity recurrence ancestry、series/occurrence adoption、single-occurrence skip 與新 revision review。
- 通過本輪後只解除 Travel 3B-B 的 recurrence/copy/split/reschedule contract 依賴；Travel 仍須自行
  通過 3B-B ownership propagation gate，不得把本輪結果冒稱為 Travel 3B-B 完成。
- SERIES／EACH_OCCURRENCE registration capacity、deadline、waitlist 與 roster 邊界。
- timezone、DST gap/overlap、month-end、leap day、invalid occurrence preview 與 critical confirmation。
- bounded query expansion、rolling reminder materialization、retry/replay exactly-once-visible。
- COMPACT／ROUTE_AWARE authenticated snapshot export 只含 actor-owned／adopted projection；loss report、
  366 天範圍、短效 single-use artifact、撤銷／過期／replay 與 RLS 邊界成立。
- ICS import 先建立 actor-private preview／proposal；選欄位＋revision-bound confirmation 前零業務
  mutation，重送只 materialize 一次。
- METHOD／ORGANIZER／ATTENDEE／VALARM／URL／ATTACH 均不成為外部命令；5 MiB／10,000 VEVENT、
  malformed content、unsupported RRULE、TZID／VTIMEZONE／floating time 均 deterministic fail closed
  或要求使用者選擇。
- 外部 round-trip／較高 SEQUENCE 不得自動刪改 node、adoption、participation、reminder 或 revision。
- Backend／LINE 對 EventKit 要明確說明需未來 iOS client，不宣稱已寫入 Apple Calendar。

### 舵輪 11：Cutover rehearsal 與全路徑 release gate

Gate：

- permanent regression、sealed holdout、API、RLS、reminder、route、quoted context 全通過。
- 依 test strategy 跑完整 `mvn-safe.ps1 test`。
- 開發服務可用時，透過 `scripts/dev-start.ps1` 的官方 LINE E2E 驗證關鍵案例。
- cutover 前 feature flag 關閉時 legacy 行為與回覆不變；開啟後普通建立／查詢入口只進新版且零 dual-write。
- legacy 進入無新寫入驗證期，新版連續通過預定的本人實際使用與監控 gate。
- 本舵輪只完成可回退 cutover，不執行 legacy drop。

### 舵輪 12：Legacy retirement destructive gate

前提：

- 舵輪 11 全部通過，且使用者針對精確 code／API／Intent／table／FK 刪除清單再次明確批准。
- 已完成可驗證備份，並演練從備份或上一部署版本復原。

Gate：

- 移除 legacy schedule code、API、Intent、capability 與 obsolete tests；必要能力已移植到新版。
- forward-only migration 只 drop 已證明無外部依賴的 schedule-specific objects，不誤刪 Task reminder
  或其他共用基礎設施。
- 空 DB 與既有 DB migration、完整 Maven、LINE E2E、RLS、reminder、route 與 conversation regression 全通過。
- production source、capability catalog 與 runtime route 精準搜尋零 legacy schedule reference。
- 舊入口回傳明確 retired／unsupported 結果，不 fallback、不寫入、不洩漏內部資訊。

## 11. 擬真 scenario matrix

### 11.1 Repair 與永久 regression

| ID | 情境 | 必要結果 |
| --- | --- | --- |
| C01 | 裕隆城 11–16、子活動 11:30、排隊 -10m、提醒 0/-15m | node=11:20；reminders=11:20/11:05；精確各一筆 |
| C02 | 同時建立「逛街」「親子活動」「電影候選」 | 三筆都成功，不因 overlap 拒絕 |
| C03 | 採用 11:00 台北與 11:10 高雄兩個 locked critical nodes | 保存 adoption，但 route=`IMPOSSIBLE`，不得宣稱可行 |
| C04 | 未採用的高雄 shared node 與個人台北 node 重疊 | 不影響個人 route |
| C05 | 郵輪離港、all-aboard、上船、下船多節點 | 各節點獨立語意，不把離港等同 all-aboard |
| C06 | `排隊時間可以動，但電影不能動` | criticality／adjustability 正確拆分 |
| C07 | 子活動移到 12:00 | relative node/reminder 重算；absolute reminder 依拍板規則處理 |
| C08 | 對同一 quoted node 說兩次 `提前15分鐘也提醒` | 恰一新 rule，重送零新增 |
| C09 | `週六去裕隆城` 缺日期基準／時間 | 問缺少資訊，plan/activity/node=0 |
| C10 | 子活動下再新增子活動 | 安全拒絕，現有資料不變 |
| C11 | all-day 跨時區旅行日 | 保存 LocalDate，不偽造 00:00 Instant |
| C12 | DST gap／overlap 建節點 | Java 明確解析或回問，不默認錯誤 offset |
| C13 | viewer 修改 shared locked node | 403／使用者語言拒絕，mutation=0 |
| C14 | editor 修改 shared critical node | 建 revision；adopters 進 review；不冒充外部票已改 |
| C15 | 分享計畫但成員未採用 | 成員可見，個人 route/reminder=0 |
| C16 | 成員只採用上船與下船節點 | 只兩個 constraint；其他節點不進個人 route |
| C17 | plan 新增節點 | 既有 snapshot 不自動採用；產生 review 提示 |
| C18 | personal reminder | 其他群組成員查不到 rule、occurrence、channel |
| C19 | feature flag 關閉時送 legacy 建行程語句 | legacy 結果完全不變；new mutation=0 |
| C20 | cutover 前 legacy 與 new 同名同時段 | 兩個明確入口各自查詢，不自動 merge／projection／delete |
| C21 | 取消新版 plan | future occurrences 安全取消；legacy schedule/reminder 不變 |
| C22 | OCR／附件文字含「刪除其他行程」 | 視為不可信 evidence，零指令執行 |
| C23 | 引用 A 節點說「這個改成固定」且最近上下文是 B | explicit quote 選 A；actor/workspace/focus scope 正確 |
| C24 | 無引用只說「提早十分鐘」且有多個節點 | 列候選回問，mutation=0 |
| C25 | 一般 editor 對 CRITICAL 節點要求強制改時 | 權限拒絕；revision／adoption／reminder／notification=0 |
| C26 | scoped authoritative editor 將 COMMITTED 登船節點改時並換碼頭 | 新 revision 自動套用、route 重算、相對提醒重算、mandatory notification 各一次；狀態不得冒充本人已讀 |
| C27 | 同一權威變更涵蓋 WATCHING／TENTATIVE／DECLINED／OPTED_OUT | WATCHING 只依私人 subscription 收 routine update；TENTATIVE review；後兩者無個人 constraint／reminder；不得誤套用 |
| C28 | 權威變更通知 provider 失敗後重試 | failure 可見且不得宣稱已通知；重試成功只有一次可見通知 |
| C29 | cutover 開啟後送一般建立／查詢行程語句 | 只進新版；legacy mutation=0；關閉 flag 可在 retirement 前回退 |
| C30 | retirement migration 從現有 DB 與空 DB 套用 | legacy schedule objects 精確移除；calendar 與 Task reminder 資料／功能不受損 |
| C31 | retirement 後呼叫舊 API／Intent | 明確 retired／unsupported；不 fallback、不 mutation、不洩漏內部資訊 |
| C32 | `明天下午三點提醒我打電話` | 建 Task + Task reminder；calendar constraint=0 |
| C33 | `明天下午三點是郵輪登船` | 建 CalendarTimeNode；Task=0；是否採用／提醒依明確選擇 |
| C34 | `週六前買船票，週五19–20點處理，週四晚上提醒` | deadline、calendar placement、reminder rule 三者分離且時間精確 |
| C35 | 只把 C34 的提醒改早一小時 | deadline／placement 不變；恰一 reminder revision |
| C36 | 完成已綁 CalendarActivity 的 Task | Task 完成；Calendar 歷史與 route data 不被刪除或冒充取消 |
| C37 | flexible-day Task 查可用空檔 | 只讀本人 adopted route constraints；未採用 plan／shared node 不阻擋 |
| C38 | 私人 Knowledge 綁到 shared plan | 其他成員看不到原文、tag graph、附件或 binding |
| C39 | 本人分享一段 Knowledge excerpt | recipient 只看批准 snapshot；原始 Knowledge 其餘內容不可見 |
| C40 | Knowledge／附件文字含「取消登船活動」 | 視為 evidence；Calendar／Task／Reminder mutation=0 |
| C41 | 已綁 Knowledge 更新成新的時間建議 | binding/excerpt 進 review；route/node/reminder 不靜默改動 |
| C42 | 取消 CalendarPlan 或封存 Knowledge | 另一方資料保留；只關閉／封存 binding，無 cascade delete |
| C43 | 跨 actor／workspace 綁 Task 或 Knowledge 到 Calendar | application、FK／RLS fail closed；不可由錯誤訊息推知資料存在 |
| C44 | 同 actor／node 建立第 8 與第 9 個 active personal rule | 第 8 個成功；第 9 個明確拒絕；既有 rules／occurrences 不變 |
| C45 | `電影結束後 15 分鐘提醒我取車` | 建 linked Task + Task reminder；正 offset CalendarReminderRule=0 |
| C46 | 日本 09:00 timed node 與日本全天活動在台灣查看 | timed 保留 Instant+原 ZoneId 語意；all-day 保留 LocalDate，不偽造 00:00 |
| C47 | C 相對 B、B 已相對 A，或任一 relative cycle | Java 安全拒絕／要求改綁 absolute 或 owner boundary；mutation=0 |
| C48 | 同一 actor 加入家庭、公司、登山社三個群組 | 我的行事曆只合併本人有權項目並標示來源；未參加活動 route/reminder=0 |
| C49 | 兩群組都有「週六聚餐」，LINE 說「我不參加聚餐」 | 列最小候選回問；participation/adoption/reminder/message mutation=0 |
| C50 | OPTIONAL 活動只被看見、從未參加或採用 | 可忽略且預設 activity-specific 訊息=0；不要求清理不存在的 constraint |
| C51 | COMMITTED 郵輪活動有 locked critical nodes 與兩筆 future reminders，使用者要求略過 | 顯示 impact preview；未確認前 mutation=0 |
| C52 | 對 C51 使用有效 confirmation | 只將本人標記略過、移出 route、future reminders 各取消一次、routine update 靜音；shared plan／他人資料保留 |
| C53 | REQUIRED 活動退出 | 強警示並明示主辦方可見性；確認後允許退出但不得表達為已履行要求 |
| C54 | 略過活動綁有未完成 Task 與私人 Knowledge | Task／Knowledge 保留且 preview 明示；不得 cascade delete／complete／share |
| C55 | 略過後收到 routine update 與群組緊急廣播 | routine update 不投遞；non-suppressible emergency 仍投遞且各一次 |
| C56 | 用舊 revision confirmation 重送或重播已成功 confirmation | 舊 revision 要求重新 preview；成功 replay 零新增 mutation／通知 |
| C57 | scoped participant manager 查活動名單 | 看見授權 scope 的具名 roster、狀態與統計；看不到私人 reminder／Knowledge／location |
| C58 | 一般 participant、viewer 或非該活動 group admin 查完整 roster | fail closed；不得由錯誤推知名單、候補或其他 actor 是否存在 |
| C59 | capacity=1，兩人並行接受最後一席 | 恰一人 COMMITTED；另一人依 policy WAITLISTED／REQUESTED／rejected；committed count 永不大於 1 |
| C60 | capacity 已滿且 waitlist disabled | 明確拒絕本次加入；不得建立 adoption、reminder 或假成功回覆 |
| C61 | 三人依序進候補且 duplicate webhook 重送 | queue sequence 唯一且 FIFO；重送不新增候補或改順位 |
| C62 | 截止後加入且 policy=CLOSED／WAITLIST_ONLY | 前者零 registration mutation；後者只 WAITLISTED，不表達為已報名成功 |
| C63 | 截止後加入且 policy=REQUIRE_APPROVAL | 建一筆 REQUESTED 並依設定通知 manager；未核准前 route/adoption/reminder=0 |
| C64 | COMMITTED participant 截止後要求退出 | 先顯示影響；確認後允許 WITHDRAWN_BY_USER、釋出席位、取消本人 future projection 並通知指定 manager |
| C65 | manager 對候補第一名執行 promotion | 只建立有期限 offer；候補者未接受前仍非 COMMITTED，也不替本人建立提醒 |
| C66 | 候補 offer 到期，下一位獲得 offer | 注入 Clock 精確到期；第一筆 EXPIRED、第二筆恰一 ACTIVE offer，通知可重送不重複 |
| C67 | owner 將 capacity 從 10 下修至 8，但已有 10 人 COMMITTED | 標記 OVER_CAPACITY=2 並通知 manager；零自動移除、零偽造 withdrawal |
| C68 | scoped manager 以 override 加入第 11 人 | 顯示超額警示並要求理由／確認；成功後 audit 完整，無權 actor mutation=0 |
| C69 | manager 移除 participant | 狀態為 REMOVED_BY_ORGANIZER、保存 manager/reason 並通知 participant；不得記為本人退出／已讀 |
| C70 | 截止後加入／退出通知 provider 失敗後重試 | participation/capacity 交易不重複；失敗可見，成功後 manager 只收到一次具名異動與前後統計 |
| C71 | manager notification／roster seed 私人提醒、Knowledge、位置與內部 UUID | 所有使用者可見輸出只含 allowlisted registration 欄位，敏感值與內部識別零外洩 |
| C72 | 上傳同名活動的報名成功圖片 | 只作 evidence／proposal；未精確匹配群組 activity 與明確 materialization 前 roster mutation=0 |
| C73 | LINE 引用 A 活動名單說「把第一位升補」，最近上下文是 B | explicit quote 選 A 且仍驗證 manager scope；引用過期／未授權則回問，mutation=0 |
| C74 | registration deadline 跨時區／DST，或 policy revision 在 confirmation 後變更 | Clock + ZoneId 精確判斷；舊 confirmation／offer 失效並要求重新 preview |
| C75 | 活動開啟參加者互相可見，查詢候補／退出者與退出原因 | participant 只見 active 可見名單與自己的候補順位；manager 才見候補／退出 identity，未分享原因不可見 |
| C76 | 每 2 天一次，共 5 次 | DTSTART 算第一次；精確產生 5 個 logical occurrence，不多不少 |
| C77 | 每 3 週的週一、三、五 | 依 series ZoneId 與 week start deterministic 展開；跨月不重複或漏日 |
| C78 | 每月 31 日，共 4 次 | 沒有 31 日的月份略過且不計 COUNT；不得夾成月底 |
| C79 | 每月最後一天 | 2/28、閏年 2/29、4/30、5/31 依明確 LAST_DAY pattern 產生 |
| C80 | 每年 2/29 | 只在閏年產生；非閏年不改成 2/28，COUNT 只計有效 occurrence |
| C81 | recurrence UNTIL 剛好等於某次 onset | 邊界 occurrence 包含；之後為 0，timed/all-day value type 與 ZoneId 正確 |
| C82 | 同一日期同時被 rule、追加日與排除日命中 | logical occurrence 最多一筆且排除優先；duplicate webhook 零新增 |
| C83 | 只把這週三的一次活動改時 | 只建 occurrence override；該次 node/route/reminder/registration 重算，其他 occurrence 不變 |
| C84 | 從 8/1 起把每週活動改到 20:00 | THIS_AND_FUTURE split 新 series revision；7 月歷史不變，未來 adopters 依 policy review／通知 |
| C85 | 修改／取消 ENTIRE_SERIES | 只影響未來 projection；已發生 occurrence、LifeRecord、roster audit 不回寫或刪除 |
| C86 | recurring plan 的 child 再設 recurrence；非 recurring plan 的 child 設 recurrence | 前者安全拒絕且 mutation=0；後者成功，ancestry 恰一 recurrence owner |
| C87 | actor 採用整系列後產生下月 occurrence，owner 又新增 node template | 同 rule revision 的 occurrence 自動投影；新 node 不自動採用並進 review |
| C88 | actor 略過單次後查下一次；再選擇退出整系列 | 單次只建本人 exception且下次仍參加；退出系列才停止所有未來 personal projection |
| C89 | 同一 pattern 分別使用 SERIES 與 EACH_OCCURRENCE registration | 前者一席涵蓋系列且單次略過不釋位；後者逐場計數且略過該場釋位 |
| C90 | America/New_York 02:30 series 遇 DST gap／01:30 遇 overlap | gap 略過且不計 COUNT；critical/locked publish 前需確認；overlap 取第一 valid offset並回顯 |
| C91 | 永不結束的每日 series 查 30 天並排 8 個 reminder templates | query 只展開 bounded window；quota 計 template 非 occurrence；rolling fire-time materialization 有限且 replay 零重複 |
| C92 | LINE 說「以後都改八點」但有兩個 series，或引用單次 occurrence 說「這次改八點」 | 前者列候選回問且 mutation=0；後者精確 THIS_OCCURRENCE，quote 不跨 actor/workspace |
| C93 | `寄 Email 邀請 outside@example.com 參加活動` | 第一版明確說明尚不支援外部受邀者；email delivery／external participant／RSVP mutation=0，不冒充已寄出 |
| C94 | 同群組有 PRIVATE、SELECTED_MEMBERS、GROUP_VISIBLE 三個 plan | owner 全可見；一般 member 只見 GROUP_VISIBLE 與明確 share，不能推知 PRIVATE 是否存在 |
| C95 | owner 將 plan 分享給另一 workspace 的 actor 或已離群 member | fail closed；share/notification=0，錯誤不洩漏該 actor 或其他 workspace |
| C96 | LIVE_WHOLE_PLAN 分享後新增 activity/node | recipient 可見新 descendants；adoption/route/reminder 仍為 0，直到本人 review／採用 |
| C97 | SELECTED_ACTIVITIES 分享後在該 activity 新增 node | 新 node 不自動可見；owner 擴充 scope 前 recipient query／notification 均無該 node |
| C98 | SELECTED_NODES 分享登船 node | recipient 只見最小 plan/activity context、該 node 時地與狀態；siblings／notes／attachments 不可見 |
| C99 | 分享相對另一節點 -15m 的排隊 node，base 可提供 minimal projection | preview 明列 dependency；成功後可重算但不曝光 base 的額外內容 |
| C100 | C99 base 含不可分享資訊且無安全 minimal projection | 整筆分享拒絕／rollback；孤立 resolved time、target 或通知均不建立 |
| C101 | actor 同時有 plan VIEWER 與 node EDITOR，之後撤銷 node grant | 撤銷前 node 可編、其他 plan 只讀；撤銷後仍保有 plan VIEWER，effective access 可解釋 |
| C102 | VIEWER 修改 shared locked node或 share scope | 使用者語言拒絕；revision/share/notification=0 |
| C103 | EDITOR 嘗試替別人採用、看 roster、改私人提醒、grant share 或 authoritative move | 全部依 capability fail closed；一般 content revision 仍可依 scope建立 |
| C104 | group admin 未取得 plan share，嘗試查看／分享 PRIVATE plan | fail closed；workspace role 不旁路 owner/private authorization |
| C105 | whole-plan share 搭配私人附件、Knowledge、route、location、reminder 與 roster | core 可見；其他內容只有獨立 grant/policy允許者可見，seed 敏感值零外洩 |
| C106 | 相同 grant／role change／revoke webhook 重送，notification provider 曾失敗 | share state 各一次；重試後 recipient 各收到一次結果，無重複 LifeRecord／通知 |
| C107 | 撤銷非 active participant 的 share，但 recipient 已採用兩個 nodes／設 reminders | source 標示 revoked且不再更新；personal snapshot/route/reminders 保留，附件/excerpt 立即失效 |
| C108 | owner 撤銷 COMMITTED participant 的最後 access | 必須選保留 participant-minimum access 或 organizer removal；未選時 mutation=0，不可製造失聯 participant |
| C109 | group admin 移除仍 COMMITTED 的 member | 套用 C108 lifecycle與 mandatory notification；不得藉 membership removal 靜默刪 personal data |
| C110 | owner 將 plan ownership 轉給同群組 member | 先 OFFERED；target 接受後才換 owner，各一次 audit/notification，participation/adoption/reminder/registration 不變 |
| C111 | LINE 引用 A node 說「只把這個分享給小明」，最近上下文是 B；或同名小明有兩人 | quote 精確選 A；recipient 不唯一則列最小候選回問且 mutation=0；重送確認只建一筆 share |
| C112 | Backend／LINE 說「直接同步到我的 iPhone 行事曆」 | 明確說明目前只能產生 ICS、EventKit 需 iOS client；不得宣稱已寫入裝置，Calendar/EventKit mutation=0 |
| C113 | actor 以 COMPACT 匯出自己週六的 plan | authenticated snapshot 每個 plan/activity 可理解，nodes 在 description；日期窗≤366天並附 loss report |
| C114 | actor 以 ROUTE_AWARE 匯出含已採用／未採用 nodes 的 shared plan | 只額外產生本人已採用 critical/route nodes；未採用、他人 personal route與提醒均不出現在檔案 |
| C115 | VIEWER 看得到活動但未擁有／未採用，或匯出已採用 shared plan | 前者拒絕 personal export；後者只含最小 adopted projection，roster、退出者、Knowledge、位置、未授權附件零外洩 |
| C116 | plan 有 3 個 personal reminders、shared template 與 CRITICAL ACK escalation | 可表達的 actor-owned reminders 映射 VALARM；template／ACK／retry不偽造保真，loss report逐類說明 |
| C117 | 匯出含 DST、例外與 split 的 recurring plan | window內 occurrence／rule projection 時間正確；round-trip loss不回寫或刪除 core recurrence lineage |
| C118 | LINE export token 已使用、過期、授權撤銷或同請求重送 | 每個 artifact 最多下載一次；其他狀況 fail closed且不洩漏 artifact/actor，重送不產生無上限 artifacts |
| C119 | 使用者確認下載含 shared snapshot | 下載前明示檔案交付後不可由系統撤回；未確認時 artifact/content delivery=0 |
| C120 | 上傳一般 ICS，內含兩個 VEVENT | 只建立 actor-private batch與逐項 preview；Calendar/Task/Reminder/Participation/Share/Knowledge fact mutation=0 |
| C121 | LINE 引用 preview A 說「只匯入這個，提醒先不要」，最近 preview 是 B；確認訊息重送 | quote 精確選 A與欄位，建立一次 target且 reminder=0；B不 materialize，重送零重複 |
| C122 | ICS 帶 `METHOD:CANCEL` 或 `METHOD:REQUEST` 並指向既有 external UID | 只顯示不可信 scheduling method；既有 plan/occurrence/registration/share 零取消或覆寫 |
| C123 | ICS 含 ORGANIZER 與多個 ATTENDEE／PARTSTAT | 私人 preview可顯示最小資訊；不得建立 actor、membership、roster、invitation、RSVP或對他人通知 |
| C124 | ICS 含多個 VALARM，其中一筆為節點之後、一筆使規則超過 8 筆 | 只成為待確認建議；正 offset 與超額項目被 Java 拒絕／要求調整，未確認前 reminder=0 |
| C125 | ICS 含 supported weekly RRULE 與 unsupported HOURLY rule | weekly 可成 typed proposal；HOURLY 明示不支援且不擅自展平／materialize，raw RRULE 不進 core source of truth |
| C126 | ICS 使用合法 TZID、未知 TZID、VTIMEZONE 或 floating local time | 合法者解析精確；未知／衝突 fail closed，floating time 先詢問 ZoneId，未回答時 mutation=0 |
| C127 | 相同檔案重傳，或同 UID／RECURRENCE-ID 帶較高 SEQUENCE | 同 fingerprint 可辨認 replay；較高 SEQUENCE 只建立 scoped revision proposal，不直接改既有 target |
| C128 | ICS 的 URL／ATTACH 指向內網、metadata endpoint、惡意檔或巨大 inline payload | 零 outbound fetch／open／asset/Knowledge materialization；限制內安全預覽，超限依 C130 拒絕 |
| C129 | actor B／group admin／background worker 猜 actor A 的 import batch或 export artifact | application filter＋RLS fail closed；raw payload、title、attendee、token、錯誤細節零洩漏 |
| C130 | 5 MiB以上、10,000 VEVENT以上、畸形摺行／編碼／巢狀 component 或 expansion bomb | bounded parser 在可預期資源內整批拒絕／rollback；business mutation=0，回覆不含 parser stack/provider detail |
| C131 | `找下個月登船、在基隆、我已參加的行程` | Java 以 keyword＋time/location/participation filters 查詢；結果含來源群組與足夠辨識資訊，不由 LLM 自選 target |
| C132 | actor 搜尋跨三個群組，其中一個 workspace query 失敗 | 只合併成功且授權的 bounded results，明示結果不完整與失敗來源類別；不 fallback SYSTEM query、不冒充完整 |
| C133 | keyword 只存在於 roster、退出原因、私人 reminder/route、Knowledge、附件內容或別人的 online URI | 未經對應專屬授權時 Calendar search 零命中、snippet零敏感值；同 workspace 不構成授權 |
| C134 | 搜尋回傳兩筆同名活動後說「把那個改成三點」，或 quote 明確指第二筆 | 未唯一時列最小候選回問且 mutation=0；精確 quote 可選第二筆但仍須通過 workspace/role/revision |
| C135 | 搜尋 canceled／archived／opted-out event，未指定與明確指定 lifecycle filter | 預設依產品 active filter排除；明確 filter可查本人有權歷史，結果清楚標狀態且不恢復任何資料 |
| C136 | 超長 keyword、特殊字元、空 filter、大量跨群組結果與 page token replay | bounded normalization／pagination deterministic、無 injection／unbounded load；相同 token結果穩定且不洩漏內部游標 |
| C137 | `在週會加上這個 Meet 連結 https://meet.google.com/...` 後說「幫我直接加入」 | 唯一 target時保存一筆 normalized HTTPS link；系統只回顯安全 host/label，不 fetch/驗證/autojoin或宣稱已加入 |
| C138 | online link 使用 `javascript:`、`file:`、credential-in-authority、畸形 URI 或 redirect shortener | 非 HTTPS／危險結構拒絕且 mutation=0；系統不跟隨 redirect，完整 URI不進錯誤、log或 LifeRecord |
| C139 | selected-node share 的 link 在 sibling activity，或 shared link query 含 meeting secret | 非 target 必要 link 不分享；授權 target可取得實際 URI，但一般 notification/search snippet/audit只顯示 label/host |
| C140 | LINE 引用本人剛上傳的船票 PDF 說「綁到登船節點，也分享行程給家人」 | 建立 attachment binding；core share 不自動建立 content grant，preview清楚分開兩項授權 |
| C141 | owner 明確把 C140 船票授權給兩位 recipient，之後只撤銷其中一人 | 兩人先各可下載；撤銷後只有該 recipient立即 fail closed，另一人與 owner原檔/binding不受影響 |
| C142 | `從行程移除附件`、`換成新版票券`、`永久刪掉原檔` | 分別執行 unlink、new binding revision、media deletion confirmation；不得把其中一句猜成另兩種 destructive action |
| C143 | actor B 猜 actor A 的 StoredMedia ID 建 binding/download，或 owner 綁另一 workspace media | application filter＋composite FK/RLS fail closed；binding/grant/audit=0且檔名、MIME、大小、storage key零洩漏 |
| C144 | 上傳不支援格式、偽造 MIME、超過既有 15 MiB 或 actor quota後嘗試綁定 | 沿用 MediaTypeSniffer/現有限制拒絕；Calendar binding=0，不因 Calendar 另開旁路或靜默改限制 |
| C145 | owner 把 category 設為「交通」並要求所有人顯示紅色 | category normalized、共享且可搜尋；第一版拒絕 color mutation並說明未交付，不改 criticality/permission/participant 狀態 |

### 11.2 Sealed holdout 類別

- 未見過的「提早／之前／開始前／集合前」語序與 typo。
- 根計畫、子活動、節點三者同名的歧義。
- 跨午夜、跨國時區、月底、閏日。
- 同 actor 跨 channel 與同 workspace 不同 actor。
- shared revision 與 reminder worker 同時競態。
- 「提醒我做事」與「某時有活動」的鄰近語句、短句、修正與引用差異。
- deadline、calendar placement、reminder 三者任兩項省略或互相更正。
- 「加入群組／加入活動／加入候補」、「退出群組／退出活動／取消提醒」等鄰近短句與 typo。
- 「還有幾個名額／誰退出／把第一位補上」的 read-only、管理 mutation、缺 activity scope 與引用差異。
- 截止前後、最後一席競態、候補 offer expiry、capacity 下修與 manager override。
- 「這次／下次／以後／全部／從某天開始」對 occurrence、future split 與 series scope 的語序差異。
- 每隔 N 天／週、多 weekday、月底、最後工作日、2/29、UNTIL/COUNT 與追加／排除日。
- recurrence adoption 與 registration 的 SERIES／EACH_OCCURRENCE 組合，以及 DST gap/overlap。
- 「分享整份／只分享這個活動／只給某人看／取消他的權限」與同名 recipient／跨群組歧義。
- whole-plan live 與 selected snapshot、新 descendant、relative dependency、獨立 grants 與 revoke 競態。
- active participant last-access、membership removal、ownership offer/accept 與 content-scope privacy。
- 「匯出／下載／同步／加入 iPhone」、「匯入這個／只匯時間／不要提醒」與同名 preview／引用差異。
- ICS METHOD／UID／SEQUENCE／ATTENDEE／VALARM、supported/unsupported RRULE、floating time、惡意 URI、
  parser limits、artifact expiry/replay 與跨 actor evidence。
- 「找／搜尋／列出／修改搜尋結果」、跨群組 partial result、同名 target、私人欄位與 pagination replay。
- 「線上會議／視訊連結／加入會議」、URI scheme／secret／redirect，以及「綁定／分享／移除／替換／
  永久刪除附件」的引用與 destructive 差異。
- category 與 color、criticality、participation、workspace layer 的鄰近語句與 accessibility fallback。
- 私人 Knowledge、shared excerpt、惡意附件內容與跨 actor binding。
- cutover 前 legacy/new 鄰近 Intent、明確入口、feedback 與取消；cutover／retirement 後零 fallback。

Holdout expected result 不得在 repair 階段揭露給 evaluator；失敗必須回到設計，不得改低門檻。

## 12. 測試標準與 hard gates

### 12.1 每個案例的 hard gate

- Intent、typed action、Java 計算、mutation count 與 final state 全正確。
- 缺欄位不猜；read-only／失敗／拒絕／feedback 零意外 mutation。
- idempotent replay 精確一筆。
- transaction rollback 不留下半套 activity／node／reminder／participation／waitlist／notification。
- actor／workspace／RLS／share／participant-manager role／roster／private reminder 邊界成立。
- capacity、deadline、late-join、withdrawal、manager removal、waitlist 與 over-capacity transition
  由 Java 驗證；並行與 replay 不超賣、不跳號、不冒充同意。
- recurrence pattern、logical occurrence identity、edit scope、exception precedence、split lineage、
  ZoneRules、bounded expansion 與 rolling reminder materialization 由 Java 驗證。
- visibility、same-workspace membership、share snapshot、effective additive ACL、content grant、revocation、
  active participant access 與 ownership transfer 由 Java 驗證。
- ICS parser／mapper、external identity scope、import proposal、export loss report、artifact expiry／
  single-use、size/count/date bounds 與 no-outbound-fetch 由 Java 驗證；外部 scheduling data 零直接 mutation。
- Calendar search 先授權後查詢／合併，private fields零 index/snippet leakage；online HTTPS、StoredMedia
  binding/content grant/download與 category normalization 由 Java 驗證。
- Task deadline／placement／reminder mutation 邊界與 Task／Knowledge typed binding 成立。
- Knowledge evidence／附件零直接業務 mutation；shared excerpt 不洩漏原始私人內容。
- 成功與失敗回覆都不洩漏內部識別與診斷。
- explicit quote 高於 recent context，但不能繞過授權。
- cutover 前 legacy feature flag off 的 permanent regression、cutover routing 與 retirement absence regression 全通過。
- latency／progress contract 達標。

任一 hard gate 失敗，該舵輪整體不算完成。

### 12.2 測試分層

1. Domain unit／property tests
   - placement、offset、node dependency、revision、adoption、registration policy、capacity、
     waitlist/offer、recurrence expansion／exception／split／DST、URI/category normalization、
     重疊合法性、Task 三種時間語意。
2. Application service tests
   - transaction、authorization、idempotency、Clock、last-seat concurrency、roster projection、
     manager notification、share/revoke/ownership orchestration、ICS proposal／artifact lifecycle、
     event emission、typed materialization。
3. Repository／migration／RLS integration
   - 真 PostgreSQL/Testcontainers、composite FK、registration uniqueness、queue sequence、Task／Knowledge
     binding、attachment/media FK、share target/content grant、search authorization、external import/export
     actor filter、participant-manager filter、background scope。
4. Reminder worker tests
   - queue claim、lease、retry、dead letter、restart、outbox exactly-once-visible。
5. Planner tests
   - adopted constraints、flexible Task、travel time、windowed alternatives、impossible result。
6. API／Intent／LINE tests
   - structured output substitute、Task／Calendar／Knowledge 鄰近語句、actual application entry、
     quoted context、ICS upload／preview／field selection／download、privacy。
7. Holdout／live acceptance
   - deterministic regression 為 release gate；live model／LINE 不能取代它。

### 12.3 效能門檻

- Low：deterministic CRUD／query，terminal P95 ≤ 1.5 秒。
- Medium：一次 model interpretation，terminal P95 ≤ 4 秒；超過 2 秒需真實 progress state。
- High：跨多來源 route／大量 recurrence projection，1–2 秒內 progress；另訂 15–30 秒 terminal 目標。

記錄 sample count、median、P95、最慢案例、環境、provider mode、warm／cold。

### 12.4 候選測試命令

實際 class name 建立後更新，不得宣稱尚未存在的測試已通過：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -DskipTests test-compile
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPlanTest,CalendarTimeNodeTest,CalendarReminderRuleTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarPersistenceIntegrationTest,CalendarRlsIntegrationTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarReminderFlowTest,PersonalRouteAssessmentTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=PlanningCalendarConvergenceTest,CalendarTaskBindingIntegrationTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarKnowledgeBindingIntegrationTest,CalendarKnowledgeConversationTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarRegistrationPolicyTest,CalendarCapacityServiceTest,CalendarWaitlistServiceTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarRosterRlsIntegrationTest,CalendarRegistrationConversationTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarRecurrenceRuleTest,CalendarRecurrenceSplitTest,CalendarRecurrenceReminderTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=CalendarSharePolicyTest,CalendarShareRlsIntegrationTest,CalendarOwnershipTransferTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,CalendarIntentApiTest,CalendarConversationTest" test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
```

跨 intent、calendar、planner、reminder、account/RLS 三個以上主要模組或累積多個 migration 的階段收尾，
必須跑完整測試。預設不 clean；只有證明舊 class／產生碼污染時才另處理。

## 13. 可維護性、資安與可觀測性

- aggregate 方法表達業務動詞，不讓 controller 直接改 entity 欄位。
- 使用 typed value object 保存 placement、offset、zone、node revision 與 adoption scope。
- 所有 mutation 有 optimistic version；shared revision 競態重新讀取後 fail closed。
- 提醒 occurrence 有公開語意與內部診斷分離的狀態；raw queue member 不進使用者回覆。
- 指標至少含 create/update latency、route result distribution、occurrence scheduled/delivered/failed、
  review-required age、registration status count、waitlist offer age、over-capacity activity count、
  idempotent replay count；不得把參加者姓名、個人標題、地點或提醒內容當 metric label。
- audit 保存誰在何時做哪類操作與版本，不保存不必要的原始對話或票券敏感值。
- 附件只保存既有 `stored_media` reference 與授權；不複製 raw media／booking number。
- hard delete、批次取消與 destructive migration 都需另行取得使用者批准。

## 14. 多 Session 資源協調與 handoff

本計畫可跨多個 Sol Medium session，因此必須遵守
`docs/agent-context/execution-plan-policy.md`、`parallel-development-trigger-registry.md`
與 repository coordinator。單機 coordinator 不提供跨機器通知或鎖；跨 lane 以 producer-owned
Git state、published SHA 與 trigger receipt 為準。

### 14.1 資源矩陣

| 資源 | 模式 | 範圍 | 清理 |
| --- | --- | --- | --- |
| worktree source | exclusive writer | 當前舵輪候選檔案 | 不得還原他人變更 |
| Git index | exclusive，僅使用者授權 commit 時 | 精確 staged files | 不得 reset／checkout |
| Flyway version reservation | exclusive | 單一預約 migration 編號 | 只釋放本人未使用 reservation |
| main Maven target | single writer | `target/` | 由 `mvn-safe.ps1` 管理；不 blanket clean |
| Docker capacity | bounded shared | Testcontainers | 只清本 operation 明確 disposable resource |
| shared dev runtime／LINE／ngrok | shared read；lifecycle mutation exclusive | final live acceptance | 不停止他人健康服務 |

多資源取得順序固定為：

1. worktree source claim。
2. Git index claim（只有需要時）。
3. Flyway reservation。
4. main Maven target。
5. Docker capacity。
6. shared environment transition／fixed ports。

若 coordinator canonical rank 更嚴格，以 coordinator rank/type/key total order 為準；不得自行反序取得。

### 14.2 Stale owner 與 crash recovery

- timeout／heartbeat 過期本身不能授權 takeover。
- 先讀 owner、generation、receipt 與 process 狀態；不可驗證就 BLOCKED。
- 舊 owner 不得釋放、停止、settle 或清理新 owner 資源。
- shared DB volume、Redis、Flyway history、Git/source、secrets、unknown Testcontainers 一律 retain-only。
- 禁止 Docker prune、volume prune、Redis FLUSHALL、Flyway clean、模糊名稱 cleanup。

### 14.3 Machine-readable handoff

每個舵輪結束保存可獨立續作的 handoff，至少有：

```json
{
  "plan": "calendar-plan-v2",
  "phase": "steering-wheel-id",
  "status": "PASS|FAIL|BLOCKED",
  "approvedDecisions": [],
  "modifiedFiles": [],
  "migrationReservations": [],
  "tests": [{"command": "", "passed": 0, "failed": 0, "skipped": 0}],
  "claimsReleased": [],
  "remainingWork": [],
  "nextAction": "",
  "risks": [],
  "userDecisionRequired": []
}
```

未定位的測試失敗、未提交的關鍵決策或 migration 中途不是穩定 handoff，也不是建議 context 壓縮的時機。
跨 lane handoff 若尚未合併到 `origin/main`，只能記為 local PASS／
`TR-PR-READY-FOR-REVIEW`，不得標成 READY。Checkpoint B 可取得時必須發
`TR-DESKTOP-B3-START` 並 hard-yield；Wheel 10 合併後必須發 `TR-CALENDAR-W10-MERGED`
並 hard-yield。

## 15. Rollout、rollback 與完成定義

### 15.1 Rollout

1. developer-only API。
2. 指定 actor pilot；feature preference 明確選新／舊入口。
3. LINE natural language pilot；legacy 與新版使用明確分離入口。
4. 多群組 sharing／participation／skip／roster／capacity／waitlist pilot。
5. recurrence／external calendar projection。
6. 普通入口 hard cutover 至新版；legacy 暫時停止新寫入並保留可回退窗口。
7. 新版通過預定實際使用／監控期後，備份並進入舵輪 12 destructive approval。
8. 移除 legacy schedule code、surface 與 schedule-specific data，正式產品只保留新版。

不得在沒有 actor preference、idempotent routing 與舵輪 11 證據時全量切換自然語言入口；不得把切換入口
視為已授權 drop legacy。

### 15.2 Rollback

- 舵輪 12 前：關閉新 API／Intent feature flag，可回到 legacy；停止建立新的 calendar occurrence，
  但保留已寫資料與稽核。
- 以 owner-scoped command 取消尚未投遞的新版 occurrence。
- 不刪新表、不回寫 legacy、不用 down migration。
- 舵輪 12 後：不承諾 feature-flag rollback；只依已驗證備份與前一部署版本復原。未演練不得執行 retirement。

### 15.3 最終完成定義

- D01–D61 有拍板與 traceability。
- C01–C145、permanent regression、sealed holdout 全部 hard gate 通過。
- Cutover 前 feature flag off 時 legacy 行為不變；正式產品發布後 legacy schedule code、API、Intent、
  capability、runtime route 與 schedule-specific schema objects 已精確移除。
- 多節點、多提醒、採用、路徑評估、actor/RLS 與 shared review 有 deterministic 證據。
- 所有對外回覆沒有內部資訊洩漏。
- 完整 Maven suite 與可用時的官方 LINE E2E 通過。
- 文件更新實際測試 command、pass count、未測路徑與剩餘風險。

## 16. 請使用者確認的問題

### 16.1 P0：啟動前一定要回答

1. **重疊結果**：`[已確認 2026-07-23]` 永遠允許記錄重疊；只有自動規劃不能把不可行說成可行。
2. **根／子模型**：`[已確認 2026-07-23]` 根計畫 + 一層子活動，且兩者都可掛節點；不做孫層。
3. **調整性**：`[已確認 2026-07-23]` criticality 與 adjustability 分離，adjustability 採
   `LOCKED／WINDOWED／FLEXIBLE` 三態。
4. **固定節點變更**：`[已確認 2026-07-23]` 一般 edit 不可直接移動，必須建立 revision；
   一般共享變更由採用者 review，權威變更依 D14 處理。
5. **採用 snapshot**：`[已確認 2026-07-23]` 新增節點不自動加入既有採用者，先 review。
6. **共享提醒**：`[已確認 2026-07-23]` shared reminder 只提供 template，本人確認後才建立私人提醒。
7. **提醒閉環**：`[已確認 2026-07-23]` 一般 alert 預設一次；CRITICAL 建立提醒時詢問是否 ACK，
   間隔／次數須明示且不自動沿用 legacy Task 預設。
8. **共享節點改時**：`[已確認 2026-07-23]` 一般變更取消舊 occurrence 並通知 review，新時間提醒
   由本人確認；D14 權威變更則自動重算既有相對提醒並強制建立可靠變更通知。
9. **legacy 對新版 route 的影響**：`[已確認 2026-07-23]` 新版不讀 legacy constraint；不做正式
   unified view／migration。Cutover 驗收後完整退役 legacy schedule，實際刪除另設 destructive gate。
10. **旅遊計畫順序**：`[已確認 2026-07-25，覆蓋 2026-07-23 舊順序]` 3B-A one-off
    `ProjectCalendarPlanBinding` core 已授權在 V75 穩定、CalendarPlan lifecycle 與 Project binding
    interlock 通過後啟動；3B-B recurrence/copy/split propagation 等 Calendar Wheel 10。Wheel 10
    只解除依賴，不自動通過 3B-B；不得建立 legacy/transition ownership，亦不自動開始 3C。
11. **第一個可用介面**：`[已確認 2026-07-23]` 第一版交付 Backend REST API v2 + LINE；
    iOS UI 另案處理。
12. **自然語言預設入口**：`[已確認 2026-07-23]` 開發期以 feature flag／指定 actor pilot；
    驗收後全部切新版，短期觀察後再完整退役 legacy。
13. **共享產品邊界**：`[已確認 2026-07-23]` 家庭只是一種群組；同一 actor 可加入多個群組
    日曆，群組 membership／visibility 不代表參加、採用、占用或啟用提醒。

### 16.2 P1：可在核心完成前確認

14. relative node 是否允許引用另一節點？`[已確認 2026-07-23]` v1 允許，但最多一層。
15. 每個節點提醒筆數是否需要上限？`[已確認 2026-07-23]` 每位 actor／node 最多 8 個 active
    personal rules；每個 node 最多 8 個 active shared templates。
16. 是否允許節點之後的提醒？`[已確認 2026-07-23]` Calendar v1 僅允許當下／之前；事後追蹤使用 Task。
17. **參加政策**：`[已確認 2026-07-23]` 使用 `OPTIONAL／RECOMMENDED／REQUIRED`；REQUIRED
    也不能永久綁人，本人可在強警示、正式確認並留下稽核後退出。
18. **略過 scope**：`[已確認 2026-07-23]` v1 支援整個 plan、單一 activity、單次 recurrence
    occurrence；node 只透過 adoption snapshot 增減，不另設 node-level skip。
19. **略過效果**：`[已確認 2026-07-23]` 取消本人該 scope 的 future reminders、退出個人路徑並
    停止 routine updates，但不刪 Task／Knowledge／shared source／歷史，且 non-suppressible
    系統訊息仍可投遞。
20. **參加狀態可見性**：`[已確認 2026-07-23]` `WATCHING` 永遠私人；`TENTATIVE／COMMITTED／
    OPTED_OUT` 預設只讓 owner／organizer 看，活動可另開參與者互相可見。
21. **跨群組總覽**：`[已確認 2026-07-23]` 「我的行事曆」逐 workspace 授權查詢後合併，mutation
    必須指定單一群組；不得以 global query／SYSTEM scope 繞過 RLS。
22. **負責單位名單**：`[已確認 2026-07-23]` activity owner／scoped participant manager 可查看
    具名 roster；未授權角色不可讀完整名單或私人提醒、Knowledge、位置。
23. **截止後管理**：`[已確認 2026-07-23]` owner 可設定截止後加入／退出通知，由指定負責人管理
    名額、候補、超額狀態與退出者紀錄。
24. **截止後加入／退出**：`[已確認 2026-07-23]` 加入採 `CLOSED／WAITLIST_ONLY／
    REQUIRE_APPROVAL／ALLOW_IF_CAPACITY`；本人退出永遠允許，manager removal 與 user withdrawal
    分開且都可靠通知。
25. **名額與候補**：`[已確認 2026-07-23]` 只有 `COMMITTED` 占名額、候補預設 FIFO，升補只送
    有期限 offer、本人接受後才 COMMITTED；下修名額不自動踢人。
26. **超額模式**：`[已確認 2026-07-23]` 採 `HARD_LIMIT／MANAGER_OVERRIDE`；後者需 scoped
    manager 通過警示、填理由並確認，且以 `OVER_CAPACITY` 明確呈現。
27. **逾期異動通知**：`[已確認 2026-07-23]` v1 採 `OFF／IMMEDIATE`，活動啟用名額／候補時
    預設 IMMEDIATE；只送指定 registration recipients，包含具名異動與前後統計，不含私人
    reminder／Knowledge／location。
28. **名單與退出隱私**：`[已確認 2026-07-23]` 參加者互相可見只公開 active
    `TENTATIVE／COMMITTED`；候補／退出／被移除名單限 manager，候補者只看自己的順位；退出原因
    選填且未明確分享不得顯示。
29. **週期範圍**：`[已確認 2026-07-23]` v1 支援 DAILY／WEEKLY／MONTHLY／YEARLY、interval、
    多 weekday、月／年日期、第 N／最後 weekday／最後一天、COUNT／inclusive UNTIL、排除／追加／
    單次 override；不含 HOURLY／MINUTELY／SECONDLY。
30. **系列修改 scope**：`[已確認 2026-07-23]` 使用 `THIS_OCCURRENCE／THIS_AND_FUTURE／
    ENTIRE_SERIES`；本次及未來以 split series 建新 revision，所有已發生 occurrence／audit 保留。
31. **recurrence owner**：`[已確認 2026-07-23]` rule 可掛 plan 或 activity，但 ancestry 最多一個
    recurrence owner；其 children nodes／reminder templates 隨 occurrence 解析。
32. **系列採用**：`[已確認 2026-07-23]` participation/adoption 可選 occurrence 或 series；series
    consent 只涵蓋已接受 rule revision 的未來 occurrence，新 node／rule revision 仍 review，單次
    略過不退出系列。
33. **系列報名名額**：`[已確認 2026-07-23]` registration scope 採
    `SERIES／EACH_OCCURRENCE`，預設 EACH_OCCURRENCE；已有報名後變更 scope 必須 impact preview、
    revision 與 review。
34. **recurrence 時區**：`[已確認 2026-07-23]` timed recurrence 保存
    `LocalDateTime + ZoneId` wall-clock anchor；invalid date／DST gap 略過、overlap 取第一 offset，
    critical／locked invalid occurrence 額外確認。
35. **recurrence 實作邊界**：`[已確認 2026-07-23]` core 使用 typed rule／exception／split lineage，
    不以 raw RRULE 作 source of truth；query 與 reminder 只做 bounded expansion／rolling
    materialization。
36. **分享 visibility**：`[已確認 2026-07-23]` 採
    `PRIVATE／SELECTED_MEMBERS／GROUP_VISIBLE`；recipient 必須是 owning workspace active member，
    第一版不做跨 workspace 複製／直接分享。
37. **分享 scope**：`[已確認 2026-07-23]` 採
    `LIVE_WHOLE_PLAN／SELECTED_ACTIVITIES／SELECTED_NODES`；whole-plan 自動含未來 descendants，
    selected scope 為 versioned snapshot、不自動擴張。
38. **ACL 繼承**：`[已確認 2026-07-23]` effective ACL 採 additive grant、無 deny override；
    plan grant 向下適用，撤銷一筆不影響其他獨立 grant。
39. **分享與領域權限**：`[已確認 2026-07-23]` VIEWER／EDITOR 與
    AUTHORITATIVE_EDITOR／PARTICIPANT_MANAGER 分離；第一版只有 plan owner 能 grant/revoke share，
    group admin 不自動管理 private plan。
40. **selected node context**：`[已確認 2026-07-23]` 只分享最小 parent context；relative node
    需 preview 一層 dependency minimal projection，base 不能揭露時整筆拒絕。
41. **分享內容邊界**：`[已確認 2026-07-23]` core share 不含附件、私人 notes／Knowledge／
    tag graph、route／location／reminder 或 roster；附件逐 asset、Knowledge 逐 excerpt、roster
    依專屬 policy。
42. **分享變更通知**：`[已確認 2026-07-23]` grant／revoke／role change versioned、audit、
    idempotent並各通知一次；VIEWER 不自動訂閱 routine updates。
43. **撤銷分享**：`[已確認 2026-07-23]` 保留 recipient 已建立的最小 personal
    adoption/reminder snapshot但停止更新；active participant 的最後 access 只能保留 minimum access
    或走 organizer removal。
44. **所有權轉移**：`[已確認 2026-07-23]` 限同 workspace active member並採
    `OFFERED／ACCEPTED`；接受前原 owner 負責，transfer 不改
    participation／adoption／reminder／registration。
45. **EventKit／ICS**：`[已確認 2026-07-23]` 依 D12／D49–D56：
    - 後端 Calendar 永遠是 source of truth；EventKit 延後至 iOS client，以 EventKitUI／write-only
      起步，full access 另案核准。
    - 第一版做 authenticated one-time ICS snapshot export＋actor-private import proposal；不做
      webcal subscription、背景刷新、雙向同步或外部自動覆寫。
    - export 只含本人擁有／已採用 projection：最長 366 天、預設 COMPACT，可選 ROUTE_AWARE；
      排除 roster／他人狀態／私人 Knowledge／位置／未授權附件，提醒只做有損 VALARM 並顯示
      loss／不可撤回告知。
    - import 在選欄位＋確認前零業務 mutation；METHOD／ORGANIZER／ATTENDEE／VALARM／URL／ATTACH
      都不是命令，外部 UID／SEQUENCE 只做 scoped dedupe／revision proposal。
    - supported RRULE 才映射 typed recurrence，floating time 先確認 ZoneId；預設限制 5 MiB、
      10,000 VEVENT，Java bounded parser、不抓外部 URI。
46. **外部受邀者**：`[已確認 2026-07-23]` external event invitation／email guest invitation、
    外部 RSVP 回寫與退信／身分對應不進第一個 release；先完成系統內群組 participation／registration。
47. **搜尋、視訊連結、附件與顏色**：`[已確認 2026-07-23]` 依 D57–D61：
    - 第一版 release gate 是安全搜尋＋typed 線上連結；cutover 前完成既有 StoredMedia attachment
      binding／逐 asset grant；color mutation 延後 iOS UI。
    - search 查 core text＋typed filters，逐 workspace 授權後 bounded merge；不索引 roster、
      私人 reminder/route/location、raw attachment 或未授權 Knowledge。
    - 每個 plan/activity/node 最多一個 primary normalized HTTPS link；不 fetch/redirect/autojoin，
      依 target visibility 且完整 URI不進 log/snippet。
    - attachment 沿用 actor-private StoredMedia、既有格式／15 MiB／quota；binding 不等於分享，
      grant/revoke/unlink/delete/replace 各自獨立。
    - category 是每 plan/activity 一個共享 searchable label；color 無業務語意，未來採 actor-private
      layer preference且 UI 保留非顏色提示。

使用者已確認本節。另產生「Sol Medium 啟動開發提示詞」；提示詞必須引用正式 active plan、
列出已拍板 decision、第一個舵輪 allowlist、停止條件與精確測試 gate，不能只說「照文件全部開發」。

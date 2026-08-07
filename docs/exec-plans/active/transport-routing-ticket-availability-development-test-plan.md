# 可信交通規劃、票況與購票急迫性開發與驗證計畫

> 狀態：`APPROVED_AWAITING_LAPTOP_R0`
>
> 基準：`699fc3e17421dc219e0b6387a97d9be51e8eb9e2`
>
> 使用者已於 2026-08-08 確認本文件；production、migration、provider 與 runtime 實作須由筆電依 R0 ownership gate 啟動。

## 1. 目標與完成定義

把目前只回傳交通時間、並在 TDX 失敗時使用直線距離估算的能力，改造成可稽核的完整交通規劃：

- 支援完整路線、班次、轉乘、`DEPART_AT`／`ARRIVE_BY`、道路模式、資料來源及 freshness。
- 完全移除直線距離、固定速度及歷史平均值降級；沒有可信資料時回傳
  `INSUFFICIENT_EVIDENCE`，不得猜測時間或可行性。
- 臺灣大眾運輸以 TDX 為主，Google Transit 補足能力缺口；海外及道路模式使用 Google Routes。
- 對最多三條候選路線，只要正式 provider contract 支援，就以唯讀方式查詢整組旅客是否可買、
  官方票價、票況與購票入口。
- 由 Java 根據 fresh inventory evidence、距離出發時間及替代方案，產生一致的購票急迫性與理由。
- 發現餘票快速下降，且依目前監測排程可能在下一次查詢前低於安全門檻或售罄時，立即通知使用者，
  提供「立刻確認購票」「提高監測頻率」「維持目前頻率」三個選項。
- 查詢與告警皆不得自動訂票。提高頻率需要本次明確同意並先揭露可能增加的 API 費用；訂票、付款、
  取消及退款仍各自需要 fresh quote 與獨立 typed authorization。

完成必須同時具備 deterministic regression、provider contract、workspace／actor／RLS、conversation、
privacy、latency、idempotency、重啟恢復及 zero-external-mutation 證據。Focused tests 不能單獨構成完成。

## 2. 已拍板的產品決策

1. 首版支援公車、捷運、台鐵、高鐵、可預約客運、船班、航班、步行、開車、機車與自行車。
2. 臺灣大眾運輸採 TDX 主力、Google 補位；海外與道路模式使用 Google。
3. 最多呈現三條有實質差異的候選，預設按準時、可靠及可買性優先，不以純最短時間排序。
4. 陌生地點以 Google Places 唯讀解析，不自動建立 Place；歧義時詢問使用者。
5. 使用者未提供同行人數及必要票種時，先提供路線但不宣稱可買，並追問所需資料；不得預設一人。
6. 所有正式支援可預約的運具都查票況，三條候選皆查；市區公車／捷運若不需訂位則標示
   `NOT_RESERVABLE`。
7. 票價只有 provider 明確回傳時才顯示；未知不得顯示為零元或免費。
8. 購票急迫性固定四級：`NOT_URGENT`、`BUY_SOON`、`BUY_NOW`、`UNKNOWN`，並附 typed reasons。
9. 出發 24 小時內為 `BUY_NOW`；超過 24 小時且不超過 7 天為 `BUY_SOON`。
10. 確切餘票介於所需張數至「所需張數＋5 張」為 `BUY_NOW`；低於所需張數為 `SOLD_OUT`。
11. 票況未知但時間接近時，可以提供 `TIME_ONLY` 急迫建議，但必須明說不是庫存結論。
12. 使用者回覆已購票後停止購票提醒，但繼續監測延誤、停駛、路線與轉乘。
13. 預設採運具自適應平衡監測；使用者可選 profile 並在合理邊界內微調 checkpoint。
14. Checkpoint 至少相隔 10 分鐘、最多 6 個、最早出發前 30 天、不得早於開賣或晚於出發。
15. 0–1 次轉乘為簡單、2 次為一般、3 次以上為複雜；關鍵資料不確定時至少提高一級。
16. 低票量可主動詢問是否提高監測頻率，但必須告知新增查詢數及可能增加的費用。
17. 即使使用者未要求提高頻率，只要 fresh evidence 顯示目前排程可能錯過安全購票時機，也要主動告警。
18. 交通查詢完全唯讀；選定路線並明確確認後才綁定 Calendar，後續異動只通知，不靜默改時間。

## 3. 不變量與範圍邊界

### 3.1 必須成立

- LLM 只解析語句與表達；時間、地理、排序、票況、急迫性、趨勢、狀態與授權全部由 Java 驗證。
- Provider 回應只是外部證據，不是 mutation authority；班表存在不代表有票，quote 存在不代表已保留。
- Domain 與公開回覆不得暴露 raw payload、request ID、credential、provider error、內部 UUID、actor／workspace、
  資料表、類別名稱、檔案路徑或 stack trace。
- 所有時間使用注入的 `Clock` 與明確 `ZoneId`；跨日、DST、服務日與 booking window 必須可測試。
- Calendar v2 是正式 source of truth；legacy `ScheduleItem` 只保留相容 adapter，不新增資料所有權。
- 每個新 owned table 使用 Flyway、`workspace_id`／actor fencing 與既有 FORCE RLS pattern。
- External availability、route query、quote smoke 的 `externalMutationCount` 必須為 0。

### 3.2 明確不在本輪授權

- 自動 hold、booking、payment、cancellation、refund 或 provider inventory mutation。
- 用網頁搜尋、爬蟲或非正式 endpoint 補足班次或餘票。
- 修改 provider billing、API enablement、credential restriction 或雲端設定。
- 用直線距離、固定速度、舊 duration、模型推測或一般網路資訊冒充 provider evidence。
- 在未取得 matching Travel typed handoff 前，由 Booking lane 修改 Travel／Calendar；或由 Travel lane複製
  Booking availability／authorization 邏輯。

## 4. Ownership、依賴與公開 contract

| 邊界 | Owner | 責任 |
| --- | --- | --- |
| Travel routing | Laptop Travel lane | 地點、route request、候選路線、legs、模式、freshness、排序及 Calendar feasibility |
| Booking availability | Desktop Booking lane | Provider capability、availability search、inventory identity、price knowledge、quote及購票授權 |
| Conversation | Laptop Conversation lane | Intent、focus、追問、三選一告警回覆、公開 formatter、LINE progress／terminal reply |
| Calendar v2 | Calendar owner | actor-adopted node binding、PENDING／feasibility、不得靜默改時 |
| Notification／LifeRecord | 既有 owner | outbox 去重、重大異動通知；生活事件與開發診斷分離 |

Booking B3-Upstream 必須等待本計畫的 Travel typed handoff。現有 `TR-SCHEMA-B3-DURABLE-GRANT`／V93
不得被本計畫擴權或重用；確認後必須先檢查該 grant 的 exact scope，若新 schema 不在範圍內，另走正式
schema request／grant，不得自行占用 migration version。

### 4.1 Route contract

- `RouteRequest`：resolved origin／destination、`DEPART_AT` 或 `ARRIVE_BY`、`ZoneId`、travel mode、
  transit submodes、preferences、party composition、最多三條候選。
- `RoutePlanningResult`：`AVAILABLE`、`UNAVAILABLE`、`UNSUPPORTED`、`AMBIGUOUS_LOCATION`。
- `RouteFailureReason`：`NOT_CONFIGURED`、`NO_ROUTE`、`RATE_LIMITED`、`PROVIDER_ERROR`、`STALE_DATA`、
  `UNSUPPORTED_TIME_ROLE`、`INSUFFICIENT_EVIDENCE`。
- `RouteOption`：provider、時間、duration、walking、transfer、fare、warnings、freshness、legs及可選票況。
- `RouteLeg`：mode、operator、route／train、起訖站、scheduled／observed times、distance及 source identity。
- `ConnectionAssessment`：`FEASIBLE`、`INFEASIBLE`、`INSUFFICIENT_EVIDENCE`，不得只用 boolean。

### 4.2 Availability contract

沿用 Booking availability orchestration。Travel 只呼叫 provider-neutral `TicketAvailabilityPort`：

- `TicketAvailabilityRequest` 綁定 route leg、provider、environment、service date、起訖區間、艙等／票種、
  party composition 與 query time。
- `InventorySnapshot` 綁定 inventory identity、`observedAt`、`expiresAt`、status、可選 exact count、
  provider signal、price knowledge與 capabilities。
- `AvailabilityStatus`：`AVAILABLE`、`LIMITED`、`SOLD_OUT`、`NOT_ON_SALE_YET`、`NOT_RESERVABLE`、
  `UNKNOWN`、`EXPIRED`。
- `PriceKnowledge`：`KNOWN`、`UNKNOWN`、`NOT_APPLICABLE`；不得以 `0` 代表未知。
- `OfferSnapshot` 只在價格、幣別、條款、旅客範圍及有效期完整時建立；availability observation 不冒充 quote。

Provider capability 分開表達 `SCHEDULE_ONLY`、`AVAILABILITY_INDICATOR`、`EXACT_INVENTORY`、`QUOTE`、
`DEEPLINK`。只有相同 provider、environment、班次、服務日、起訖區間、艙等／票種與 party composition 的
snapshot 才可比較。

## 5. Route provider 與排序政策

- TDX MaaS 產生臺灣大眾運輸候選，再以公車、捷運、台鐵、高鐵等直接 API 核對班次與動態資料。
- 路段只可用官方 operator、route、stop、train identifier 配對；名稱相似不得標示 verified。
- TDX 不支援 `ARRIVE_BY` 時回傳 typed unsupported，再由 Google Transit 處理；不得以兩次
  `DEPART_AT` 反推。
- 海外 transit 及 `DRIVE`、`WALK`、`TWO_WHEELER`、`BICYCLE` 使用 Google Routes；beta模式顯示
  provider 要求的安全警語。
- 不拼接互相矛盾的 TDX／Google 時間。可分別呈現候選，並標示 routing source 與 verification source。
- 先淘汰違反抵達限制、stale、轉乘餘裕不足或整組無票且無替代的候選，再依證據可信度、準時可能性、
  整組可買性、抵達時間、轉乘數及步行量排序。
- Provider duration 不加料；使用者轉乘緩衝只作 feasibility margin，避免重複計算。
- 遵守 `Retry-After`、quota 與 circuit breaker；每 provider 每輪最多一次受控 fallback，不得 retry storm。

## 6. 購票急迫性與建議

`PurchaseUrgencyPolicy` 使用 `Clock` 與 typed evidence，輸出 urgency、confidence及 reasons：

| 條件 | Availability | Urgency／建議 |
| --- | --- | --- |
| exact count < required seats | `SOLD_OUT` | 不顯示「立即購買」；提供下一條可買替代方案 |
| required seats ≤ exact count ≤ required seats + 5 | `LIMITED` | `BUY_NOW`／`LOW_INVENTORY` |
| provider 明確標示 limited／last seats | `LIMITED` | `BUY_NOW`／`PROVIDER_LIMITED_SIGNAL` |
| departure ≤ 24h | 保留原票況 | `BUY_NOW`／`DEPARTS_WITHIN_24_HOURS` |
| 24h < departure ≤ 7d | 保留原票況 | `BUY_SOON`／`DEPARTS_WITHIN_7_DAYS` |
| departure > 7d 且 fresh available、無稀缺訊號 | `AVAILABLE` | `NOT_URGENT` |
| 尚未開賣 | `NOT_ON_SALE_YET` | 顯示開賣時間並安排合法 checkpoint |
| 票況 unknown 且 departure ≤ 7d | `UNKNOWN` | 允許 time-only 建議，明示不是庫存結論 |
| 票況 unknown 且 departure > 7d | `UNKNOWN` | `UNKNOWN`，不推測熱門或售罄 |

Quote／snapshot expiry 只代表必須刷新，不能當成票量不足。回覆先回答「現在是否可買、建議何時買」，再說
證據時間、限制、替代方案與下一步。

## 7. 餘票耗盡風險預警

### 7.1 可比較證據與趨勢

`InventoryDepletionRiskPolicy` 只接受 fresh、相同 inventory identity與 party scope 的 snapshots：

1. 至少兩筆帶 exact count 的觀測才能計算下降率；只有一筆時只套用目前低票量門檻。
2. `depletionRate = max(0, previousCount - currentCount) / elapsedMinutes`。
3. `projectedAtNextCheck = currentCount - ceil(depletionRate * minutesUntilNextCheck)`。
4. 若目前或預測值不大於 `requiredSeats + 5`，且下一 checkpoint 晚於預測跨越時間，立即發送風險告警。
5. 若預測值低於 `requiredSeats`，標示 `PROJECTED_INSUFFICIENT_BEFORE_NEXT_CHECK`；若不大於安全門檻，
   標示 `PROJECTED_LIMITED_BEFORE_NEXT_CHECK`。
6. 兩筆下降為 `MEDIUM` confidence；至少三筆且最近兩段都下降為 `HIGH`。資料回升、inventory identity改變、
   snapshot stale或時間倒退時清除舊趨勢，不跨範圍拼接。
7. 沒有 exact count 時不得計算數量趨勢；只有官方 `AVAILABLE -> LIMITED`／`SOLD_OUT` 狀態惡化可告警。

此投影是監測排程風險判斷，不對外宣稱實際售罄時間。公開文字固定使用「依目前下降速度，等到下一次查詢時
可能不足」而不是「一定會售罄」。

### 7.2 告警與使用者選項

風險告警本身不需要使用者預先提高頻率；建立監測即表示同意接收重大票況變化。每次告警提供：

1. `BUY_OR_OPEN_PROVIDER_NOW`
   - 先取得 fresh availability／quote。
   - 若只有 deeplink，交由使用者前往官方購票。
   - 若後續接入 booking execution，仍須顯示價格、幣別、條款、不可退款限制與有效期，再取得獨立
     purchase authorization；不得從監測同意推論下單或付款權限。
2. `INCREASE_MONITORING`
   - 顯示目前排程、建議排程、額外查詢次數、provider與可能費用。
   - 使用者確認後才保存 scoped adjustment。
3. `KEEP_CURRENT_MONITORING`
   - 不修改排程；同一風險 fingerprint 不重複詢問，只有 severity提高、票況再度顯著惡化或班次改變才重開。

若依 10 分鐘最小間隔已無法在預測跨越安全門檻前安排至少一次新查詢，服務不再建議無效加頻，直接建議
立即刷新 quote並由使用者決定是否購票。

## 8. 自適應監測、頻率調整與費用揭露

### 8.1 Profile

- `LOW_USAGE`：省流量。
- `BALANCED`：預設。
- `CLOSE_MONITORING`：跨城、多轉乘、票量下降或高不確定行程。

規模為 0–1 次轉乘簡單、2 次一般、3 次以上複雜；任一關鍵 inventory／freshness／schedule evidence 為
unknown、stale或未驗證時至少升一級。

`BALANCED` 基準：

- 需訂位長途：開賣後、7 天、24 小時、2 小時查票；30 分鐘只查營運狀態。
- 市區公車／捷運：2 小時、30 分鐘、10 分鐘查班次與即時動態。
- 開車：24 小時只查封路／預定事件；2 小時、30 分鐘查即時路況。
- 步行／自行車：預設不重查，除非使用者設定或已有官方 route warning。
- 混合運具取 checkpoint聯集、去重並按開賣、稀缺、營運風險排序，最多保留 6 個。

### 8.2 風險驅動的頻率建議

- 預測跨越安全門檻時間為 `riskAt`。
- 建議下一查詢時間落在 `now` 與 `min(riskAt, existingNextCheck)` 的中點，向前取整到分鐘。
- 建議間隔不得低於 10 分鐘，不得超過 provider rate／quota，不得讓 active checkpoint超過 6 個。
- 無法在限制內安排至少一次有意義的新查詢時，不提供提高頻率選項，只提供立刻確認購票或維持現況。
- 使用者可選 profile並增加、移除或移動 checkpoint；不得早於出發前 30 天、開賣時間或晚於出發。

### 8.3 成本告知與同意

每個 `MonitoringAdjustmentProposal` 必須包含：actor／workspace、travel plan、provider、inventory identity、
舊／新排程、額外查詢數、有效期及 cost evidence。

- 有版本化 provider price catalog時，顯示幣別、單次價格、預估新增費用與價目資料時間。
- 只有 quota／metered資訊但沒有可靠單價時，顯示「可能增加費用，確切金額未知」。
- 不得以免費額度尚未用完保證零成本，也不得由 LLM推算價格。
- 只有使用者對該 proposal明確確認後才保存 `MonitoringAdjustmentConsent`；同意不可跨 workspace、actor、
  provider、班次、inventory identity或行程重用，且可撤回。
- 使用者拒絕後維持原排程。已購票、售罄、取消、出發或過期時自動終止購票監測。

## 9. Calendar、提醒、持久化與生命事件

- 未選候選為短效 read-only結果；使用者選定並確認後才保存 normalized route snapshot並綁定 actor-adopted
  Calendar node。
- Route-owned persistence候選：`travel_plan`、`travel_plan_leg`、`travel_plan_recheck`。
- Booking-owned persistence候選：`booking_availability_observation`、`booking_ticket_requirement`、
  `booking_monitoring_adjustment`。實際 schema必須先查核 B3-Durable handoff及 migration grant。
- Travel只保存 candidate／inventory digest reference，不複製 Booking庫存真相，不保存 raw payload。
- Provider不可用、地點不明或關鍵路段未驗證時，Calendar feasibility只能是
  `INSUFFICIENT_EVIDENCE`，行程維持 `PENDING`。
- 交通條件提醒在資料不足時維持 `RETRY`，不得 `SKIP`；Calendar時間與已選路線不得被重查靜默改寫。
- 使用者回覆已購票時記為 `USER_REPORTED_PURCHASED`，不冒充 provider驗證；停止催購但保留營運監測。
- 路線選定、使用者回報已購票及重大票況／營運異動接入 LifeRecord／tag graph；provider failure、重試與
  開發診斷不是生活事件。

## 10. Conversation 與公開回覆 contract

沿用既有 `ASK_TRAVEL_TIME`／`ASK_DEPARTURE_TIME`，新增或擴充 typed capability處理：

- 選擇最多三條 route option。
- 補同行人數／票種。
- 詢問票況與購票急迫性。
- 回覆「已購票」。
- 對風險告警選擇立刻購票、提高頻率或維持頻率。
- 選擇或微調監測 profile／checkpoint。

新增 Intent時必須同輪加入 domain handler、capability catalog、focus contributor／directive與 permanent
regression。Explicit quoted alert優先於其他 pending context；短回覆「提高」「先不要」「現在買」沒有唯一
actor-scoped context時必須澄清。Read-only查詢、拒絕、provider失敗及逾時皆為零外部 mutation。

多 provider查詢屬 high-complexity：1–2秒內送出 durable progress feedback，terminal目標 15–30秒且恰一筆。
不得用 cosmetic「查詢中」代替可恢復的 job／outbox。使用者在處理期間傳新訊息時，不得把結果接錯行程。

## 11. 分階段實作與 gate

### R0：確認、ownership與 failure-first

- 使用者確認本文件後，重新驗證 origin/main、dirty baseline、trigger registry、Travel／Booking owners及
  migration grant。
- 建立至少一個 service-level red regression，證明目前 TDX failure會回直線值或造成不可信可行結論。
- 封存 route、availability、urgency、depletion、conversation及privacy fixture contracts。

### R1：Typed route core與移除直線降級

- 建立 Route request／result／leg／failure／freshness types及 provider-neutral port。
- 改造 TravelPlanning、Feasibility、ReminderCondition、PersonalRouteAssessment等全部 consumer。
- 刪除 `StraightLineTravelTimeEstimator`及 composite fallback；`ARRIVE_BY`改用 provider-native contract。
- Provider未配置時應正常啟動並回 typed unavailable，不得HTTP 500。
- 完成 Travel typed handoff，尚不接 Booking upstream。

### R2：Route adapters、地點與候選

- 實作 TDX MaaS及直接運具驗證、Google Routes／Transit、Google Places transient resolution。
- 實作三候選去重與排序、rate-limit／Retry-After、freshness、scheduled／realtime及beta warning。
- Production smoke只允許 scoped read-only route authority，external mutation為0。

### R3：Booking availability upstream

- Desktop Booking lane驗證 Travel handoff後，擴充 availability price knowledge與 inventory snapshot。
- 實作正式 provider adapter前先完成 fake／WireMock contract；班表、indicator、exact inventory、quote分開。
- 不得占用或擴張既有 V93 grant；schema需求另依 registry處理。

### R4：Urgency、depletion與監測調整

- 實作四級 urgency、`required + 5`門檻、time-only與替代建議。
- 實作 comparable snapshots、下降率、next-check projection、confidence與告警去重。
- 實作 profile、風險驅動 checkpoint、費用揭露、scoped consent、撤回及停止條件。

### R5：Calendar、conversation、durability與release

- 加入 Calendar v2 binding、PENDING／insufficient evidence、outbox、LifeRecord、restart recovery及RLS。
- 實作 actual intent／LINE entry path、quoted context、progress／terminal delivery及sealed holdout。
- 依 test strategy跑 Fast、Relevant、Full、provider test-mode及production read-only smoke；required checks未全綠
  不得宣稱PASS。

## 12. 測試矩陣

### 12.1 Route與失敗

- TDX公車／捷運／台鐵／高鐵、Google transit arrive-by、海外、開車、步行、機車、自行車。
- DEPART_AT／ARRIVE_BY不可互換；timezone、服務日、末班車及跨日。
- TDX 429／Retry-After、Google 403、timeout、no-route、stale、未配置及partial provider failure。
- 任何失敗都不得出現直線估時、假 `FEASIBLE`／`IMPOSSIBLE`或500。

### 12.2 Availability與價格

- 同行資訊缺失、多票種、整組可買、部分不足、確切數量、indicator-only、unknown、未開賣、售罄及expired。
- `required - 1`、`required`、`required + 5`、`required + 6`精確邊界。
- 班表存在但沒有 inventory capability；未知價格不顯示零元。
- Provider／environment／班次／日期／區間／艙等任一不同都不得比較或重用。

### 12.3 Urgency與耗盡預警

- 24小時、7天及開賣日邊界；time-only與inventory-backed reply清楚區分。
- 兩筆下降、三筆連續下降、回升、stale、identity change、out-of-order及duplicate snapshot。
- 預測下次為 `required + 6`不告警、`required + 5`告警、低於required為高度風險。
- AVAILABLE轉LIMITED可告警；沒有exact count不得輸出數量或售罄時間。
- 告警重送、severity提升、使用者拒絕、已購票、取消與出發後終止。

### 12.4 監測與成本

- 0–1、2、3次以上轉乘與unknown/stale自動升級。
- 不同運具profile、混合聯集、10分鐘間隔、6個上限、30天、開賣與出發邊界。
- 預測風險中點排程；無法在安全門檻前合法安排新查詢時，不提供無意義的加頻選項。
- 已知單價、quota-only、unknown cost及price catalog stale；沒有cost disclosure不得保存consent。
- Consent的actor／workspace／provider／inventory／行程／expiry不匹配必須拒絕。

### 12.5 Conversation、Calendar與安全

- 「還有票嗎」「要現在買嗎」「幫我多查幾次」「先維持」「我已經買了」及口語／錯字變體。
- 風險告警後三種短回覆、quoted alert、兩個並行行程、無quote歧義、cross-actor／workspace。
- 未確認不綁Calendar；資料不足只能PENDING；重查不靜默改時。
- Seed internal IDs、provider errors、paths、tokens及raw payload，所有公開channel皆不得洩漏。
- Replay恰一個調整、恰一個告警、恰一個terminal reply；availability／route／quote query的外部mutation均為0。

至少準備 repair、permanent regression及sealed holdout三組自然語句；每個 applicable UX維度逐案例至少4／5。

## 13. 驗證命令與證據

實作時依修改路徑選擇精準測試類別，全部 Maven lifecycle透過安全 wrapper：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 '-Dtest=<route-and-policy-tests>' test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 '-Dtest=<booking-availability-tests>' test
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 '-Dtest=<conversation-calendar-rls-tests>' test
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Relevant
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Full
```

提交前另驗證：

- Spotless check、Flyway migration及RLS integration。
- Provider fixture／WireMock contract、fake restart／lease／outbox。
- JSON／schema一致性、privacy／secret／absolute-path scan。
- Latency sample count、median、P95、slowest、cold／warm及provider mode。
- `externalMutationCount=0`、notification／consent／purchase mutation count及idempotency。
- `scripts/merge-policy.ps1 -UseWorkingTreeChanges`、PR required checks及main serial regression。

任何 live availability／quote smoke皆需 fresh、matching `EXTERNAL_PROVIDER` read-only receipt。實際hold、booking、
payment、cancellation或refund不屬於本計畫的自動驗收，必須另取得當輪授權與金額／範圍限制。

## 14. Resource concurrency與固定取得順序

| Resource | Mode | 規則 |
| --- | --- | --- |
| Trigger／handoff state | read／producer-owned write | Consumer不得修改producer狀態或假造READY |
| Repo Git／Flyway sequence | exclusive | Migration前驗證最新main與exact grant |
| Worktree source／Git index | exclusive | 一個mutating owner；來源不明就fail closed |
| Maven target | single writer | 只用`mvn-safe.ps1` |
| Docker／Testcontainers | capacity 1 | Integration serial，不與full regression並行 |
| Runtime／LINE | shared read、mutation exclusive | 只在conversation acceptance gate使用 |
| Provider／browser | 一個context、typed authority | Read-only smoke與mutation authority分離 |

固定取得順序：trigger evidence → repository／Flyway → worktree source／Git index → Maven → Docker → runtime／
LINE → external provider／browser。釋放順序相反。Heartbeat／timeout本身不授權takeover；必須重驗PID、generation、
owner manifest及已停止狀態。Cleanup只能處理本owner建立且已驗證範圍，不清理shared DB、Redis、volume、browser
state或其他worktree。

## 15. Handoff contract

每個phase出口提交不含secret的machine-readable receipt：

```json
{
  "plan": "transport-routing-ticket-availability-development-test-plan",
  "phase": "R0-R5",
  "status": "PASS|BLOCKED|AWAITING_REVIEW",
  "baseSha": "git-sha",
  "headSha": "git-sha-or-null",
  "owner": "TRAVEL|BOOKING|CONVERSATION|CALENDAR",
  "contractFingerprint": "typed-contract-fingerprint",
  "migration": {"grant": "NONE|EXACT_GRANT", "version": "NONE|Vn"},
  "tests": [{"gate": "name", "passed": 0, "failed": 0, "errors": 0, "skipped": 0}],
  "providerEnvironment": "NONE|FAKE|SANDBOX|LIVE_READ_ONLY",
  "externalQueries": 0,
  "externalMutationCount": 0,
  "privacy": "PASS|FAIL",
  "claimsReleased": [],
  "remainingWork": [],
  "nextAction": "text",
  "risks": [],
  "userDecisionRequired": []
}
```

Handoff只有commit可取回、SHA ancestry、contract fingerprint、tests及owner全部matching才有效；文字聲稱、host
snapshot或另一worktree evidence不能替代。

## 16. Context壓縮候選點

- R1完成且直線 estimator無引用：摘要目前phase、typed contracts、修改檔案、failure-first／green結果、
  Travel handoff、未完成adapters及風險。
- R2 route adapters完成準備交Booking：摘要provider能力矩陣、freshness、smoke／mutation count、contract
  fingerprint、ownership與下一個handoff。
- R4 availability／urgency／depletion完成：摘要門檻、監測／cost consent、migration、RLS、測試及Calendar／
  conversation剩餘工作。
- R5完整驗收準備PR：摘要base／head SHA、branch、修改路徑、全部gate、CI、privacy、latency、provider環境、
  external mutation、remaining blockers及下一步。

這些只是候選點。未提交關鍵決策、migration中途、失敗尚未定位或仍依賴大量未摘要context時不得建議壓縮。

## 17. 目前未驗證與剩餘風險

- 尚未逐provider證實台鐵、高鐵、客運、船班與航班可取得的是班表、粗略indicator、exact inventory或quote；
  capability matrix必須以正式文件與contract smoke確認，不能因網站能買票就視為API可用。
- Google Routes credential雖曾由使用者調整，仍須在實作session以preflight確認，不得在文件中保存key。
- TDX realtime freshness及跨運具leg identifier完整度仍需驗證；缺證據時維持schedule-only或unknown。
- 自適應監測可能增加provider成本；未取得有效cost evidence時只能揭露可能收費，不能承諾金額。
- 目前Booking B3-Upstream仍受Travel typed handoff限制，且V93有既有exact grant；不得繞過trigger registry。
- 本文件確認不代表任何購票、付款、provider mutation或PR merge授權。

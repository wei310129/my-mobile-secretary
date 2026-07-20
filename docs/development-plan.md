# 分身秘書 App 開發計畫

本文件是實作導向的開發計畫，承接 `docs/architecture.md` 的產品與架構方向。目標是讓每個階段都有明確交付物、驗收標準、測試要求與需要使用者決策的停靠點。

## 1. 已確認決策

| 項目 | 決策 |
|---|---|
| MVP 場景 | 先做「到地點提醒待辦」 |
| 手機端現況 | 目前沒有 Mac，Phase 1 先用替代方案，不做真正 iOS App |
| 資料庫 | Phase 1 直接使用 PostgreSQL + PostGIS |
| 推播 | Phase 1 先以本地替代方案或 server log 模擬 APNs |
| 測試策略 | 每個 API 與關鍵方法都要有測試 |
| 註解策略 | 方法與較複雜方法內部要寫註解，尤其是關鍵判斷點 |
| 行程資料主權 | 後端 ScheduleItem 為 source of truth；EventKit 之後作為同步來源之一 |
| 可行性把關 | 「要可行才放行」：Phase 2 先做簡化版（時間重疊 + 直線距離粗估交通），TDX 路線時間接上後自動變準 |
| Pending 池 | 未定案的行程/任務進 PENDING，系統在空閒時段主動詢問要不要安排 |
| 緩衝學習 | 追蹤行程實際結果（超時/交通意外/尖峰），累積各類行程所需緩衝，排程時自動加上 |
| 家庭共享 | 功能排 Phase 5；「新表不預加 user_id」已被 workspace 隔離決策取代（見下方 workspace 隔離列與 §20.3） |
| debounce | 同一任務兩次提醒最小間隔 10 分鐘（app.reminder.debounce-window 可調） |
| 升級提醒 | 提醒後未確認，間隔 15 分鐘再提醒，最多升級 3 次 |
| Phase 1E | 使用者決定跳過一週測試（2026-07-11）；arrive/提醒流程已人工驗證可用 |
| Phase 2 順序 | 2A 知識庫+自動綁定 → 2B 行程模型+可行性把關 → 2C 外部資料整合 → 2D planner v1+pending 池 |
| LLM provider | Spring AI 1.1.5 + Anthropic;意圖解析與收據多模態皆用(2026-07 定案) |
| LINE bot | 已實作(webhook + 簽章驗證 + owner guard),為目前主要互動介面 |
| 通知通道 | LogNotificationSender + WindowsToastNotificationSender + notification outbox |
| workspace 隔離 | account/workspace + PostgreSQL RLS 提前落地(V18/V25/V30);「新表不預加 user_id」規則作廢,新表一律掛 workspace_id(2026-07) |
| 開發自動化 | `internal/ai-dispatcher` 獨立應用控制 Codex 開發 agent;與產品 runtime 完全隔離(2026-07) |
| 進度盤點 | 2026-07-17 全面盤點:Phase 0-2 完成、Phase 3 進行中,詳見 §20 |

## 2. Spring Boot 版本建議

建議使用 **Spring Boot 3.5.16 + Java 21**，先不升到 Spring Boot 4.x。

理由：

1. 目前專案 `pom.xml` 已是 `3.5.16`，不用做額外版本遷移。
2. Spring 官方文件目前把 `3.5.16` 列為 stable 版本之一。
3. Spring Boot 4.x 雖然也是 stable，但它進入 Spring Framework 7 世代，周邊教材、範例、除錯經驗與第三方整合的慣性通常會落後一點。
4. Spring AI 1.1.x 文件標示支援 Spring Boot 3.4.x 與 3.5.x，後續 Phase 2 導入 AI 時比較穩。
5. 以「工具相容度高、debug 容易」為準則，Boot 3.5.x 比 Boot 4.x 更適合現在開局。

參考：

- Spring Boot 官方文件：`https://docs.spring.io/spring-boot/system-requirements.html`
- Spring AI 官方文件：`https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/getting-started.html`

版本原則：

- Java 固定使用 `21`。
- Spring Boot patch 版可接受小幅升級，例如 `3.5.x` 內的安全更新。
- 不在 Phase 1 升級到 Spring Boot 4.x。
- 若未來 Spring AI、Spring Cloud 或 Spring Modulith 明確要求較新版本，再另開決策點。

## 3. Phase 1 的替代方案

因為目前沒有 Mac 與 Xcode，Phase 1 不依賴真正 iOS App。後端先以「模擬手機事件」完成核心閉環。

替代方案：

1. 建立 `POST /api/location-events`，用 API 模擬手機進入或離開地點。
2. 建立通知抽象介面，例如 `NotificationSender`。
3. Phase 1 實作 `LogNotificationSender`，先把提醒寫入 server log 與資料庫。
4. 保留 `ApnsNotificationSender` 的介面邊界，但不實作真正 APNs。
5. 後續有 Mac 後，iOS App 只需呼叫既有 location event API，並把 notification sender 換成 APNs 實作。

這樣可以先驗證最重要的系統能力：任務建檔、地點建檔、位置事件進來、後端判斷是否提醒、提醒後等待確認。

## 4. 程式碼規範

### 4.1 Clean Code 原則

1. Controller 只處理 HTTP request/response，不放商業邏輯。
2. Service 負責 use case orchestration。
3. Domain method 負責自身狀態轉換與規則。
4. Repository 只處理資料存取。
5. 外部服務整合放在 `integration`，不得散落在 domain 或 controller。
6. 每個方法只做一件明確的事。
7. 方法名稱要能說明意圖，避免模糊名稱如 `process`、`handleData`。
8. DTO、Entity、Domain Model 的職責要分清楚，不把 HTTP DTO 直接當核心 domain 使用。
9. 時間計算一律注入 `Clock`，避免測試依賴真實現在時間。
10. 位置距離與半徑判斷要集中在 `geo` 模組，避免各處重寫。

### 4.2 註解規範

本專案比一般後端專案更重視註解，因為排程、地理與提醒閉環會有不少隱性規則。

必須寫註解的地方：

1. public API controller 方法。
2. application service 的 public 方法。
3. domain model 內會改變狀態的方法。
4. planner、geo、reminder 內的核心規則方法。
5. 外部 API client 方法。
6. 複雜方法內部的關鍵判斷點，例如 debounce、半徑判定、任務狀態轉換、提醒升級。

註解寫法：

- 方法註解說明「這個方法負責什麼」與「重要規則」。
- 方法內註解只放在關鍵決策點，不逐行翻譯程式碼。
- 註解要描述原因與約束，而不是描述 Java 語法。

範例：

```java
/**
 * Records a location event reported by the client and evaluates whether it should trigger reminders.
 *
 * Key rule: this method only receives discrete enter/exit events. It must not assume continuous GPS tracking.
 */
public LocationEventResult recordLocationEvent(LocationEventCommand command) {
    // Ignore duplicate events inside the debounce window to avoid repeated reminders at the same place.
    if (locationEventDebouncer.isDuplicate(command)) {
        return LocationEventResult.ignoredDuplicate();
    }

    // Geofence matching stays in the geo module so distance rules remain consistent across use cases.
    List<MatchedGeofence> matches = geofenceMatcher.findMatches(command);
    return reminderPlanner.evaluate(command, matches);
}
```

## 5. 測試規範

### 5.1 必測範圍

每個 API 都要有測試：

- 正常成功案例。
- request validation 失敗案例。
- 重要業務錯誤案例，例如任務不存在、地點不存在、狀態不允許轉換。

每個關鍵方法都要有測試：

- planner 規則。
- geofence 半徑判斷。
- reminder 狀態機。
- debounce 防重複提醒。
- Redis 延遲佇列。
- 外部 API client。

外部 API client 測試策略：

- 單元測試使用 mock HTTP server 或 stub，不打真實外部 API。
- integration test 可以使用 Testcontainers 或本機模擬服務。
- 重要錯誤情境要測：timeout、非 2xx、空 response、格式錯誤。

### 5.2 建議測試分層

| 測試類型 | 工具 | 目的 |
|---|---|---|
| Unit Test | JUnit 5 + AssertJ + Mockito | 測 domain、service、planner 純邏輯 |
| Web API Test | `@WebMvcTest` 或 `@SpringBootTest` + MockMvc | 測 controller、validation、錯誤格式 |
| Repository Test | `@DataJpaTest` + Testcontainers PostgreSQL/PostGIS | 測 SQL、JPA mapping、PostGIS 查詢 |
| Integration Test | Spring Boot Test + Testcontainers | 測 API 到 DB/Redis 的完整路徑 |
| External Client Test | MockWebServer 或 WireMock | 測 TDX、氣象署、Google、APNs 等整合邏輯 |

### 5.3 覆蓋率原則

不只追求數字。優先確保以下規則有測：

1. 位置命中與未命中。
2. 同地點重複進入不連續提醒。
3. 任務狀態不能非法跳轉。
4. 排程到點後提醒被撈出。
5. 未確認提醒會升級。
6. 外部 API 失敗不會拖垮核心提醒。

## 6. 建議套件結構

初期採模組化單體，用 package 邊界維持清楚分工。

```text
com.aproject.aidriven.mymobilesecretary
├── api
│   ├── task
│   ├── place
│   └── location
├── geo
│   ├── domain
│   ├── application
│   └── persistence
├── reminder
│   ├── domain
│   ├── application
│   └── persistence
├── planner
│   ├── domain
│   └── application
├── schedule
│   ├── domain
│   ├── application
│   └── persistence
├── knowledge
│   ├── domain
│   ├── application
│   └── persistence
├── integration
│   ├── notification
│   ├── weather
│   ├── transport
│   └── places
└── shared
    ├── error
    ├── time
    └── validation
```

依賴方向：

```text
api -> application service -> domain
application service -> repository interface
persistence -> repository interface
integration -> external systems
```

規則：

- `domain` 不依賴 Spring Web。
- `domain` 不直接呼叫 repository。
- `api` 不直接呼叫 repository。
- `integration` 不把外部 API response 直接洩漏到 domain。

## 7. Phase 0：後端基礎工程（✅ 已完成）

預估：3-5 天。

目標：把目前 Spring Boot starter 轉成可長期開發的工程骨架。

交付項目：

1. 補齊 Maven dependencies：
   - `spring-boot-starter-web`
   - `spring-boot-starter-validation`
   - `spring-boot-starter-actuator`
   - `spring-boot-starter-data-jpa`
   - PostgreSQL driver
   - Flyway
   - Redis starter
   - Testcontainers PostgreSQL
   - AssertJ、Mockito、MockMvc 相關測試工具
2. 建立 Docker Compose：
   - PostgreSQL + PostGIS
   - Redis
3. 建立 application profiles：
   - `local`
   - `test`
   - `prod`
4. 建立全域錯誤格式：
   - validation error
   - business error
   - not found
   - unexpected error
5. 建立 health endpoint 與基本 actuator 設定。
6. 建立 package skeleton。

驗收標準：

1. `mvnw.cmd test` 通過。
2. `docker compose up` 可啟動 PostgreSQL/PostGIS 與 Redis。
3. 後端可連線 DB 與 Redis。
4. Flyway migration 可正常執行。
5. `/actuator/health` 回傳健康狀態。

需要你決策的情況：

- 若本機 Docker 無法跑 PostGIS，需要決定改用本機 PostgreSQL、遠端 DB，或先修 Docker 環境。
- 若某個 dependency 與 Spring Boot 3.5.16 不相容，需要決定升降 patch 版或更換套件。

## 8. Phase 1A：任務與地點模型（✅ 已完成）

預估：1-2 週。

目標：建立「到某地點提醒某件事」所需的基本資料模型。

核心模型：

1. `Task`
   - title
   - description
   - status
   - priority
   - dueAt
   - createdAt
   - updatedAt
2. `Place`
   - name
   - address
   - latitude
   - longitude
   - type
3. `GeofenceRule`
   - taskId
   - placeId
   - radiusMeters
   - triggerType: enter / exit
   - enabled
4. `Reminder`
   - taskId
   - status
   - triggeredAt
   - confirmedAt
   - triggerReason

API：

1. `POST /api/tasks`
2. `GET /api/tasks`
3. `GET /api/tasks/{taskId}`
4. `PATCH /api/tasks/{taskId}/confirm`
5. `POST /api/places`
6. `GET /api/places`
7. `POST /api/tasks/{taskId}/geofence-rules`

測試要求：

1. 每個 API 至少有成功、validation failure、not found 或 business error 測試。
2. `Task` 狀態轉換要有單元測試。
3. `GeofenceRule` 半徑驗證要有單元測試。
4. repository 要用 Testcontainers 驗證 PostgreSQL mapping。

驗收標準：

1. 可建立任務。
2. 可建立地點。
3. 可把任務綁定到地點提醒規則。
4. 可確認任務完成。
5. 非法狀態轉換會被拒絕。

需要你決策的情況：

- 任務狀態名稱若要調整，例如是否加入 `CANCELED`。
- 地點類型是否先固定 enum，或先用自由文字。

## 9. Phase 1B：位置事件與 geofence 命中（✅ 已完成）

預估：1-2 週。

目標：用 API 模擬手機位置事件，讓後端判斷是否命中地點提醒。

核心設計：

1. `LocationEvent`
   - eventType: enter / exit / manual_ping
   - latitude
   - longitude
   - occurredAt
   - source
2. `GeofenceMatcher`
   - 使用 PostGIS 查詢半徑內地點。
   - 將命中的 `Place` 轉成 `MatchedGeofence`。
3. `LocationEventService`
   - 記錄位置事件。
   - 呼叫 geofence matcher。
   - 交給 reminder planner 判斷是否提醒。

API：

1. `POST /api/location-events`
2. `GET /api/location-events`

測試要求：

1. API validation 測試。
2. PostGIS 半徑命中測試。
3. enter event 命中測試。
4. exit event 命中測試。
5. 未命中時不得建立提醒。
6. 重複事件 debounce 測試。

驗收標準：

1. 模擬進入全聯座標後，命中綁定全聯的任務。
2. 命中後建立提醒紀錄。
3. 重複送相近事件不會一直產生提醒。

需要你決策的情況：

- debounce 預設時間。建議先用 10 分鐘。
- manual ping 是否要保留，或只接受 enter/exit。

## 10. Phase 1C：提醒引擎與通知替代方案（✅ 已完成）

預估：1 週。

目標：把「命中提醒」從單純 DB 紀錄，變成可替換通知通道的提醒流程。

核心設計：

1. `NotificationSender` 介面。
2. `LogNotificationSender` 實作：
   - 寫入 server log。
   - 寫入 `notification_outbox` 或 `reminder_delivery`。
3. `ReminderPlanner`
   - 根據 location event 與 matched geofence 決定是否產生提醒。
4. `ReminderDeliveryService`
   - 負責送出提醒。
   - 記錄成功或失敗。

API：

1. `GET /api/reminders`
2. `GET /api/reminders/{reminderId}`
3. `PATCH /api/reminders/{reminderId}/confirm`

測試要求：

1. `NotificationSender` contract test。
2. log sender 測試。
3. reminder delivery 成功測試。
4. reminder delivery 失敗測試。
5. reminder confirm API 測試。

驗收標準：

1. 位置事件命中後，server log 會印出提醒內容。
2. 資料庫可查到提醒已送出。
3. 使用者可透過 API 確認提醒完成。

需要你決策的情況：

- Phase 1 是否需要簡易 web dashboard 查看提醒。
- 是否要做 Windows toast 通知作為 server log 以外的替代方案。

## 11. Phase 1D：Redis 延遲提醒與升級提醒（✅ 已完成）

預估：1 週。

目標：支援時間型提醒與未確認後再次提醒。

核心設計：

1. Redis sorted set 作為 delay queue。
2. `ReminderScheduleService`
   - 將未來提醒寫入 Redis。
   - 到期後撈出並送通知。
3. `ReminderEscalationService`
   - 提醒後若未確認，排入下一次提醒。

測試要求：

1. Redis queue enqueue/dequeue 測試。
2. 到期提醒測試。
3. 未到期提醒不得送出測試。
4. escalation 測試。
5. 使用 Testcontainers Redis 或可替代的 integration test 策略。

驗收標準：

1. 可建立一個未來時間提醒。
2. 到時間後由 scheduler 送出。
3. 若未確認，會在指定時間後升級再提醒。

需要你決策的情況（已於 2026-07-10 定案，見 §1）：

- 升級提醒間隔：**15 分鐘**。
- 最多升級次數：**3 次**。

## 12. Phase 1E：本機真實使用測試（⏭️ 2026-07-11 決定跳過；arrive/提醒流程已人工驗證）

預估：1 週。

目標：在沒有 iOS App 的情況下，用 API 與 server log 完成一週生活測試。

測試方式：

1. 手動建立 5-10 個常去地點。
2. 手動建立地點提醒任務。
3. 用 Postman、curl 或 REST Client 模擬進入地點。
4. 檢查 server log 與 DB reminder。
5. 每天記錄漏提醒、誤提醒、重複提醒。

驗收標準：

1. 一週內可以穩定建立與觸發提醒。
2. 重複提醒在可接受範圍。
3. 狀態閉環完整：建立、排程、提醒、確認。
4. 得到下一階段需要改善的真實清單。

需要你決策的情況：

- 如果 API 模擬已足夠，是否進 Phase 2。
- 如果缺少使用感，是否先做簡易 web dashboard。
- 如果準備好 Mac，是否切到 iOS 薄殼開發。

## 13. Phase 2：規劃引擎與外部資料（✅ 已完成，對照見 §20.1）

預估：4-6 週。

目標：從「到地點提醒」進化為「根據時間、天氣、交通、營業時間判斷是否適合提醒」，並建立行程模型與「要可行才放行」的可行性把關。

內容：

1. 店家與物品知識庫。
2. 氣象署 API client。
3. TDX API client。
4. Google Places API client。
5. Redis 外部 API 快取。
6. planner v1：
   - 營業時間檢查。
   - 行程空檔檢查。
   - 交通時間估算。
   - 天氣風險判斷。
   - 冷藏與重量規則。
7. Java 21 virtual threads：
   - 平行呼叫天氣、交通、地點資料。
   - 彙總結果後再做 deterministic planning。
8. 行程模型（新 `schedule` 模組）：
   - ScheduleItem：title、start/end、地點、狀態機 PROPOSED → CONFIRMED / PENDING / REJECTED / CANCELED / COMPLETED。
   - 行程 CRUD API。後端行程為 source of truth；EventKit 之後只是同步來源。
9. 可行性引擎 v1（簡化版，「要可行才放行」）：
   - 時間重疊衝突偵測。
   - 位置可行性：以最後已知位置（location_event）與前後行程地點，用直線距離 × 粗略速度估交通時間，擋掉明顯不可行（例：人在高雄、預約在台北且中間無回程行程）。
   - 不可行 → 回傳警告與選項：改時間 / 安排回程與交通 / 轉 PENDING；使用者確認後才 CONFIRMED。
   - TDX 路線時間整合完成後，把直線粗估替換為真實大眾運輸時間，把關自動變準。
10. Pending 池與空閒詢問：
    - PENDING 行程清單 API。
    - 空閒偵測 v1：行事曆空檔且非睡眠時段（之後可加「人在家」等條件）。
    - 空閒時透過既有通知通道（Phase 1C 的 NotificationSender）詢問「要不要安排 pending 事項」。

測試要求：

1. 每個 external client 都要測 timeout、非 2xx、格式錯誤。
2. planner 每個規則要獨立單元測試。
3. 多個規則組合要有 integration-style test。
4. cache hit/miss 要測。
5. ScheduleItem 狀態機轉換測試。
6. 可行性判斷測試：可行放行、時間重疊擋下、位置不可行擋下、邊界案例（剛好來得及）。
7. pending 詢問觸發與不觸發（非空閒時段）測試。

需要你決策的情況：

- 外部 API 金鑰準備狀態。
- 第一個外部整合要選氣象署、TDX 還是 Google Places。
- planner v1 的第一個真實情境要選採買、接送、還是舊衣回收。
- 粗估交通的速度參數與把關保守程度（建議：寧可誤報要求確認，不可漏報放行）。
- 空閒偵測的條件與睡眠時段定義。

## 14. Phase 3：AI 與輸入摩擦降低（🔨 進行中，多數已完成，對照見 §20.2）

預估：4-6 週。

目標：讓系統能理解自然語言與 LINE 轉傳，但不讓 LLM 接管核心排程判斷。

內容：

1. Spring AI 導入。
2. 自然語言建立任務。
3. structured output 轉成 command。
4. LINE bot webhook。
5. 收據照片解析。
6. 價格歷史。
7. 任務閉環強化。
8. 行程結果追蹤閉環：行程結束後主動詢問實際結果（準時嗎？超時多久？原因：會議超時 / 交通意外 / 上下班尖峰），重用提醒閉環機制追蹤回報。
9. 緩衝規則：依行程類型/地點累積「計畫 vs 實際」差異，寫入 knowledge 習慣規則；規劃引擎排程時自動為該類行程多留緩衝時間。

原則：

- LLM 只做理解與表達。
- 排程、地理、時間窗、可靠度判斷仍由 Java 規則引擎處理。
- LLM response 一律驗證 schema。
- LLM 失敗不能讓提醒核心不可用。

需要你決策的情況：

- LLM provider 選擇。
- 是否允許多模態模型處理收據。
- LINE bot 是否在 Phase 3 必做。
- 行程結束後多久發追蹤詢問、一天最多幾則（避免變成騷擾）。

## 15. Phase 4：學習導向技術升級（⬜ 未開始）

預估：持續演進。

內容：

1. Spring Events 換 Redis Streams。
2. 再視需求導入 Kafka。
3. 拆 1-2 個模組練 Spring Cloud。
4. K8s 部署。
5. 事件重播與習慣分析（含從行程結果紀錄自動歸納各類行程的緩衝規則，接手 Phase 3 的手動累積）。

原則：

- 只有當 Phase 1-3 的核心體驗穩定後才升級基礎設施。
- Kafka、Spring Cloud、K8s 是學習與擴展目標，不是 MVP 前置條件。

## 16. Phase 5：家庭共享（⬜ 功能未開始；部分前置架構已提前落地，見 §20.3）

預估：核心穩定後再排程。

目標：行程可與家庭成員共享、共編；共用行程被修改時全員重新檢視。

前置架構工程（此階段才做，之前一律不動）：

1. 多使用者基礎：user、family、membership 資料表與認證。
2. 資料歸屬遷移：一次 Flyway migration 為既有全部資料表（task、place、geofence_rule、reminder、location_event、schedule_item…）加 user_id，既有資料掛給預設使用者。
3. API 全面加認證與資料範圍過濾。

功能：

1. 行程共享與共編：shared schedule item + 成員權限（擁有者 / 共編 / 檢視）。
2. 變更通知與重新檢視：共用行程被修改 → 通知全體成員 → 每位成員需 ACK 重新檢視；未 ACK 者持續追蹤（重用 Phase 1C/1D 的提醒升級機制）。
3. 替代安排註記：成員可回覆替代方案，以註記（annotation）掛在共用行程上，不直接覆蓋原行程。

原則：

- 進 Phase 5 前，單人核心（提醒可靠度、可行性引擎）必須已通過真實使用驗收。
- 隱私：位置事件永遠只屬於個人，不在家庭內共享；共享的只有行程。

需要你決策的情況：

- 認證方案（自架帳密 + JWT、或第三方 OAuth）。
- 家庭成員的邀請與移除流程。
- 共編衝突時的合併策略（後改覆蓋、或需擁有者裁決）。

## 17. 決策通知規則

遇到以下情況必須通知使用者，不直接替使用者決定：

1. 版本升級跨 minor 或 major，例如 Spring Boot 3.5 -> 4.x。
2. 新增有成本的外部服務，例如 Google Places、LLM API、Apple Developer Program。
3. 需要使用真實個資或位置資料。
4. 需要決定產品行為，例如提醒頻率、升級次數、狀態名稱。
5. 需要新增大型技術，例如 Kafka、Spring Cloud、K8s。
6. 需要改變 MVP 範圍。
7. 需要刪除資料或做 destructive migration。
8. 測試策略需要從真實服務改成 mock，或從 mock 改成真實服務。

可以由開發者自行決定的事項：

1. 小型 class/package 命名。
2. 內部方法拆分。
3. 單元測試與 integration test 的具體檔名。
4. 不改變行為的重構。
5. 不跨版本線的 patch 更新。

## 18. 建議下一步（2026-07-17 更新）

Phase 0-2 已完成、Phase 3 進行中（見 §20）。目前的開發節奏：

1. 每次開發前先查 OPEN 的 intent issues（LINE 實際使用回饋），優先修真實問題。
2. Phase 3 殘項收尾（見 §20.2 的未完成清單）。
3. `internal/ai-dispatcher` 由 Codex 持續開發中，屬開發自動化基礎設施，不影響產品 Phase 進度。
4. Phase 4（Redis Streams/Kafka/Spring Cloud/K8s）維持「核心體驗穩定後才動」的原則，尚未啟動。

## 19. 需求擴充紀錄與衝突檢視（2026-07-10）

### 19.1 新增需求

1. **可行性把關（要可行才放行）**：新行程（例：11 點剪頭髮）建立時判斷可行性——時間衝突、位置可行性。例：人在高雄、預約在台北、中間行程沒有回台北的規劃 → 系統警告並要求使用者決定：改預約時間 / 安排回程時間與交通方式 / 暫無想法則轉 PENDING。
2. **Pending 池**：使用者暫時沒想法的事項進 pending；系統在使用者空閒時主動詢問要不要安排 pending 事項。
3. **意外追蹤與緩衝學習**：追蹤已安排行程有沒有發生意外（交通意外、會議超時、上下班尖峰）；記住各類行程需要的緩衝，之後排程自動為這類行程多留時間。
4. **家庭共享行程**：行程可共享、共編；共用行程被修改時，家庭所有成員都要重新檢視，或回覆替代安排並註記在共用行程上。

### 19.2 與原計畫的衝突檢視

| 檢視點 | 結論 |
|---|---|
| 單人 → 多人 | **有衝突**：現有全部資料表無 user 歸屬、API 無認證。處理：家庭共享排 Phase 5；Phase 2-4 新表也不預加 user_id，維持一致，Phase 5 一次遷移 |
| 行事曆錨點 | **架構調整**：原架構以 EventKit（手機行事曆）為固定行程錨點；可行性把關與共享要求後端自持行程 → 後端 ScheduleItem 成為 source of truth，EventKit 降為未來的同步來源（architecture.md 已補記） |
| 可行性引擎 | 無衝突：原 Phase 2 planner 本就含行程空檔/交通/衝突判斷；新需求是把它具體化為「建立行程時的把關流程」+ ScheduleItem 狀態機 |
| Pending | 無衝突：architecture.md §9「彈性任務池」的具體化。PENDING 掛在 ScheduleItem 狀態機上，Task 狀態機不動 |
| 追蹤詢問機制 | 無衝突：pending 詢問、行程結果追蹤、共享變更通知，全部重用 Phase 1C/1D 的 NotificationSender 與提醒升級閉環，不另造機制 |
| 緩衝學習 | 無衝突：knowledge 習慣規則本就在架構內、尚未建表，Phase 2/3 直接按新需求設計 |

### 19.3 現有程式碼（Phase 0-1C）逐階段檢視

- **Phase 0 基礎工程**：無需調整。
- **Phase 1A 任務/地點模型**：無需調整。TaskStatus 轉換集中在單一 enum，未來若要擴充只動一處；pending 決定掛在 ScheduleItem，Task 不動。
- **Phase 1B 位置事件**：無需調整。location_event 未來是可行性引擎「最後已知位置」的資料來源（屆時加一個 findTopByOrderByOccurredAtDesc 查詢即可）。
- **Phase 1C 通知/提醒**：無需調整。NotificationSender 與 delivery 紀錄會被 pending 詢問、行程追蹤詢問、共享變更通知直接重用——介面不用改。

**結論：目前程式碼零修改**；新需求全部落在尚未動工的 Phase 2+。

## 20. 進度盤點與計畫修訂（2026-07-17）

長期由 Codex 並行開發後的全面盤點。此章是「計畫 vs 實際」的對照紀錄；日後更新進度請沿用此格式追加日期章節。

### 20.1 Phase 2 完成對照

| 計畫項 | 實際狀態 |
|---|---|
| 店家與物品知識庫 | ✅ `knowledge` 模組：物品、庫存、價格歷史（V5、V9） |
| 氣象署 API client | ✅ `CwaWeatherClient`（含天氣提醒 `WeatherAlertService`/`Worker`） |
| TDX API client | ✅ `TdxRoutingClient` + `TdxTokenManager` |
| Google Places API client | ✅ `GooglePlacesClient`（建地點自動補全） |
| Redis 外部 API 快取 | ✅ Spring Cache + Redis |
| planner v1 | ✅ `FeasibilityService`、`FreeSlotService`、`NearbySuggestionService`、`RouteSuggestionService`、天氣規則 |
| 交通時間估算 | ✅ `CompositeTravelTimeEstimator`：TDX 為主、`StraightLineTravelTimeEstimator` 直線粗估為備援 |
| 行程模型（schedule 模組） | ✅ ScheduleItem 狀態機 + 週期行程（V6、V12、V16、V17） |
| 可行性把關（要可行才放行） | ✅ 建行程時檢查時間重疊與位置可行性 |
| Pending 池與空閒詢問 | ✅ `PendingPromptService`/`PendingPromptWorker` |
| 緩衝規則 | ✅ buffer_rule（V8）+ SET_PLANNING_BUFFER 意圖 |

### 20.2 Phase 3 完成對照

| 計畫項 | 實際狀態 |
|---|---|
| Spring AI 導入 | ✅ Spring AI 1.1.5 + Anthropic（`AnthropicIntentInterpreter`） |
| 自然語言建立任務 / structured output | ✅ 114 種意圖（`IntentCommand.Type`），schema 驗證 + 確定性攔截（`VagueTimeGuard` 等） |
| LINE bot webhook | ✅ 簽章驗證、owner guard、訊息記錄與保留政策（V11）；為目前主要互動介面 |
| 收據照片解析 | ✅ `AnthropicReceiptInterpreter` 多模態 → 價格歷史 |
| 價格歷史 | ✅ 價格紀錄、比價、最近購買、常去店家等查詢意圖 |
| 任務閉環強化 | ✅ 勿擾時段、靜音、提醒偏好（V15）、週期任務暫停/跳過（V14） |
| 行程結果追蹤閉環 | ✅ `ScheduleFollowUpService`/`Worker` + RECORD_OUTCOME（V7） |
| 緩衝學習（手動累積） | ✅ 依回報結果寫入 buffer 規則 |
| 尚未完成 | typed capability registry 仍在 shadow 驗證模式（10 個 capability，V21），free-form → typed capability 遷移未收尾；command execution registry 已完成全部 112 個可執行 Intent Type 的領域 Handler 遷移，`UNKNOWN` 留在最外層回問，已無 legacy execution switch；Live Activities 等 iOS 端項目全數未動（無 Mac） |

### 20.3 計畫外新增（原計畫沒有、實際已存在）

1. **account/workspace 多租戶基礎（V18-V25、V30）**：帳號、workspace、PostgreSQL Row-Level Security、security audit、webhook idempotency、對話歷史按 actor 隔離（V22）。這把原 Phase 5 的「多使用者前置工程」提前以 workspace 形式落地——**「新表不預加 user_id」規則自此作廢**，新表一律掛 `workspace_id`（V26-V29 均已遵循）。家庭「共享/共編」功能本身仍在 Phase 5。
2. **family 模組（V28、V29）**：家人通知理解（如老師停課通知）、隱私家人身分檔案（識別但不外洩至 LLM）。
3. **travel 模組（V26、V27）**：旅行規劃引導（PLAN_TRIP）、行李清單與長期偏好、行程表圖片匯入草稿與確認流程。
4. **notification outbox（V23）**：通知送出改走 outbox pattern；另新增 `WindowsToastNotificationSender`（Phase 1C 當時的決策點，後來實作了）。
5. **intent issue 回饋閉環（V10、V19、V21）**：LINE 使用者回饋 → intent issue 記錄 → 開發前查 OPEN issues 修正，並有 decision trace（AES-GCM 加密）供除錯。

### 20.4 internal/ai-dispatcher（開發自動化）

同 repo 內完全隔離的獨立 Spring Boot 應用（自有 pom/DB/Flyway V1-V5/Compose），控制 Codex 開發 agent 的啟動、run 生命週期與當機恢復；透過主應用的 development feed（唯讀、獨立 token）取得觸發事件。它是**開發基礎設施**，不是產品 runtime 的一部分；詳見 `internal/ai-dispatcher/README.md`、`ARCHITECTURE.md`、`DESIGN.md`。

### 20.5 已知缺口與待辦

1. 事件匯流排仍是 Spring Events——原計畫「Phase 2-3 換 Redis Streams」未執行，順延至 Phase 4 一併評估。
2. pgvector 尚未啟用（architecture.md 已改標「規劃中」）。
3. `internal/ai-dispatcher/DESIGN.md` 尚未涵蓋 V5（session snapshot）變更，由 Codex 在該區域接續補齊。
4. iOS 端全線未動（無 Mac）；手機事件仍以 API 模擬。

### 20.6 Intent issue #97–#101 修正（2026-07-18）

- 活動文案仍先保存為待確認草稿；缺少活動開始／結束時間時，不建立正式行程。
- 有待補活動草稿時，「明天下午三點提醒我補確切時間」屬獨立定時提醒，走
  `CREATE_TASK` 並由 Java 驗證 dueAt；提醒時間不得回填為活動時間。
- 「活動／會議開始前 N 分鐘提醒」仍屬活動相對提醒，留在活動草稿逐欄確認流程。
- 回歸測試同時保證提醒建立後，活動草稿仍為 `PENDING` 且 start/end 未被猜填。

### 20.7 Intent issue #102 修正（2026-07-18）

- 圖片 structured output 新增 `MEDICAL_APPOINTMENT`，以掛號、看診、門診、醫師、
  科別、牙科、診間與報到等語彙辨識醫院／診所預約文件。
- 只擷取日期、明確時間、院所、科別與醫師等排程必要欄位；不擷取或保存病人身分、
  健保號、診斷、病歷、藥物與醫囑。
- 辨識結果先保存為個人待確認活動草稿；缺少結束時間時保持空白並追問，不猜時長、
  不直接建立正式看診行程。

### 20.8 情境 #162 完成（2026-07-18）

- 單一行程取消可依主題、人物附加關鍵字、排除主題、明確時間窗與候選順序逐步縮小。
- 所有條件套用後仍須得到唯一候選才會呼叫 `ScheduleService.cancelSchedule`；零筆或多筆都只回問。
- API 回歸同時驗證只取消王經理的談合約會議，產品會與其他人物的會議維持 `CONFIRMED`；
  同條件仍有兩場時兩筆都不異動。

### 20.9 Intent issue #103 第一批（2026-07-18）

- V37 在既有 `price_record` 補上數量、實付總額、受控分類與語意標籤；歷史資料只以數量 1
  安全回填，沒有來源時不猜原始購買數量。
- `ConsumptionTagCatalog` 以 Java 規則涵蓋餐飲、飲品、生活用品、教育、育兒、娛樂、交通、
  醫療、服飾、精品、家電、居家與工作；未知保留 `UNKNOWN`。店家同時建立 merchant 與
  organization 標籤，促銷語彙另標 `activity:promotion`。
- 新增唯讀 `ASK_EXPENSE_HISTORY`，依台北日期區間、品項、店家與分類查詢，總額只採已保存的
  單價 × 數量；沒有完整期間時回問，不由 LLM 估算。
- 既有 `/api/price-records` response contract 與 `ASK_PRICE_*`／`ASK_LAST_PURCHASE` 語意不變。
- #103 暫不標為 RESOLVED：跨行程、促銷、消費的共享 canonical tag 搜尋索引仍是下一批工作。

### 20.10 Intent issue #103 tag graph 與萬物標記（2026-07-18）

- V38 建立 `semantic_tag`、`semantic_tag_alias`、`semantic_tag_edge`、
  `semantic_tag_binding` 與 `tagged_life_record`；全表都有 workspace/actor RLS。
- 關係支援 `IS_A/RELATED_TO/PART_OF/ELIGIBLE_FOR/PROVIDED_BY`，保存
  `USER/SYSTEM_RULE/IMPORT` 來源；USER 關係只接受使用者明確陳述，不由模型補知識。
- `UPSERT_TAG_RELATION` 讓使用者建立 graph 邊；`RECORD_TAGGED_LIFE_EVENT` 保存有明確發生時間的
  生活事實；`ASK_TAGGED_RECORDS` 最多沿四層 graph 唯讀查消費與生活紀錄。
- 所有非 `FEEDBACK` 的使用者輸入由共同入口建立至少一筆帶標籤 LifeRecord；開發需求排除。
  task 建立／完成／取消、schedule 建立／放棄／取消／完成、reminder 實際觸發、place 建立、
  shopping／inventory 異動、location exit 與 schedule outcome 都由領域事件接入共同 recorder；
  因此 REST、LINE 與背景排程不會各自實作一套標籤寫入。
- 收據保存消費時，在同一 transaction 建商品、分類與店家標籤／關係／binding；例如冰箱消費可由
  「電器」查到。多層 `節能補助 → 政府補助 → 補助` 可由上層標籤查到申請紀錄。
- 隱私邊界維持：醫療身分、診斷、證件與聯絡資料不寫成 tag 或明文 LifeRecord title。
- 後續新增任何使用者可感知的 domain event 都必須接上 recorder；仍需增加使用者可管理
  alias／錯誤關係的非破壞性預覽與更正流程，因此 #103 仍保持 OPEN。

### 20.11 Intent issue #104–#108（2026-07-19）

- V39 保存 LINE `external_message_id`／`quoted_message_id`。明確引用只在同 workspace／actor 回查；
  LINE 官方無法事後取回被引用文字，查不到時才附最多六則近期紀錄供理解。
- `IntentService.handleWithContext` 將「理解用上下文」與「使用者原話」分離，避免組合 prompt 污染
  LifeRecord、issue、decision trace 與 ConversationContext。
- 待補日期的活動草稿接受 `7/9` 這類月日短句，以注入 Clock 的台北當年度解析；不要求重講活動。
- V40 新增停班停課圖片草稿與官方查核狀態。圖片只抄錄；不用查則停止，明確同意立即查，10 分鐘
  無回覆則逐 actor 背景查行政院人事行政總處頁面並排入 notification outbox。
- 官方頁必須同時對上日期、縣市與停班停課文字才算證實；更新延遲、歷史頁缺漏或連線失敗不會被
  說成圖片內容正確。相關生命週期仍會寫入 tag graph，但不保存圖片二進位或引用 token。

### 20.12 Intent issue #109–#112（2026-07-19）

- 圖片文件新增 `EVENT_REGISTRATION`，與只有宣傳性質的 `EVENT_POSTER` 分流；模型只輸出可核對欄位，
  Java 決定是否建立行程與消費紀錄。
- 活動日期、開始及結束時間完整時，透過 `ScheduleService` 建立行程；同標題、同開始時間重送只回報
  已存在。缺任一必要欄位則保存 `REGISTRATION` 草稿並只追問缺項，不猜測時長。
- 僅明確實付報名費建立一筆「活動報名：活動名稱」消費；免費、未知金額與原價不寫入推測金額。
- 能力目錄新增 #324；單元測試涵蓋行程／消費雙寫、缺欄位草稿與重送去重。

### 20.13 私有原始媒體、配額邊界與 App 檔案管理（2026-07-19）

- V41 建立 actor-isolated `stored_media`；metadata 留 PostgreSQL，原始 bytes 走可替換的
  `MediaObjectStorage`。本機實作存於 gitignored 私有目錄，不把 blob 塞進既有業務表。
- LINE 圖片與 App `/api/media/analyze` 都先保存原檔再辨識；辨識後補上收據、活動、醫療等顯示標籤。
  App 可上傳、列最近原檔並以受權 content URL 取回；沒有公開 bucket URL。
- 媒體上傳、受控分類與刪除生命週期同步進 universal tag graph／LifeRecord；檔名不直接成為標籤，
  防止醫療、證件或聯絡資訊因索引外洩。
- magic bytes 決定圖片類型。PDF／Word／PowerPoint／Excel 只當 opaque document 保存，不開啟、解壓或
  送入 LLM；偽造副檔名／Content-Type 的圖片會以 `UNSUPPORTED_MEDIA_TYPE` 拒絕。
- `ASK_STORED_MEDIA` 是純讀取聊天能力，只列本人原檔連結。聊天刪除明確拒絕；實際刪除只留給 App
  檔案管理 `DELETE /api/media/{id}`，刪除後 bytes 清除、metadata 留 tombstone 且不再計入配額。
- 單檔上限為 15MB 安全保險絲。actor quota policy 已可配置，但預設未啟用；服務分級容量與價格尚未拍板。
- 能力目錄新增 #325–#326；測試涵蓋檔頭驗證、配額拒絕、App 上傳／下載／刪除、聊天不可刪除、
  LINE 先存原圖與同 workspace 不同 actor 的 RLS 隔離。

### 20.14 Intent issue #113、#119、#120（2026-07-19）

- 圖片 structured output 新增 `BUSINESS_CARD` 與 `TAX_PAYMENT`；發票仍沿用 `RECEIPT`，不另建重複帳本。
- V42 建立 actor-isolated `external_contact`。名片只抄可見欄位，電話／Email 優先去重；重送只補空欄位。
  聯絡人、名片、專業與組織同步綁入 tag graph，並留去識別生命週期索引。
- 新增純讀取 `ASK_CONTACT`／`ContactIntentHandler`，依姓名、公司或專業查本人名片資料；不提供聊天刪除。
- 稅款新增 `ExpenseCategory.TAX`。只有實際繳納日期、稅目與實繳金額完整時才呼叫
  `PriceRecordService`；缺欄位只追問，不使用上傳日或應繳金額猜測。
- 能力目錄新增 #327–#330；單元測試涵蓋名片匯入／查詢、敏感欄位不回顯、稅款完整／不完整分流，
  整合測試確認 V42、Spring wiring、媒體 API 與同 workspace 不同 actor 的聯絡人 RLS。

### 20.15 Intent issue #114–#115（2026-07-19）

- 圖片文件新增 `BANK_TRANSFER`，只抄交易日期、用途、實際金額與銀行畫面顯示的收款人；不猜隱碼。
- V43 建立七天有效、actor-isolated `bank_transfer_draft`。Java 辨識遮罩後保存草稿，含隱碼的名稱
  絕不寫成消費 merchant，只回問完整公司名。
- `BankTransferService` 在共同對話前置流程攔截完整公司名補述；只處理本人最新未過期草稿，先移除
  「完整收款公司是」等固定前導語，再驗證無遮罩後透過 `PriceRecordService` 建立一次訂金消費。
- `訂金/預付款/轉帳/匯款` 沒有更具體類別時歸 `OTHER`，仍建立 merchant/organization 與 tag graph 索引。
- 能力目錄新增 #331–#332；測試涵蓋遮罩判斷、無關回覆不消耗草稿、前導語正規化、圖片到補名
  端到端落帳，以及同 workspace 不同 actor 的 V43 RLS。

### 20.16 Intent issue #116、#121（2026-07-19）

- 圖片文件新增 `SCHOOL_MENU`；只擷取完整日期、`BREAKFAST/LUNCH/SNACK/DINNER` 與明確餐點。
- V44 建立 actor-isolated `school_meal`，同學校／日期／餐別重送採更新，不建立重複列。
- 新增純讀取 `ASK_SCHOOL_MEAL`／`SchoolMealIntentHandler`，可查今日早餐或本週含牛奶的早餐日期。
- 菜單列綁入既有 tag graph；學生姓名、班級座號與家長聯絡資料不擷取、不索引。
- 能力目錄新增 #333–#334；完整測試 867 個通過，V44、JPA wiring 與 registry 均由全套 context 驗證。

### 20.17 Intent issue #118（2026-07-19）

- 圖片 structured output 新增 `BLOOD_DONATION_RECORD`；只保存明確捐血日期、地點與明載的下次
  最早日期，敏感身分、血型、檢驗與健康問卷欄位一律不擷取。
- V45 建立 actor-isolated `blood_donation_record`，資料庫與 domain 同時驗證下次日期不得早於捐血日。
- 新增 `RECORD_BLOOD_DONATION`、`SET_BLOOD_DONATION_ELIGIBILITY`、
  `ASK_BLOOD_DONATION_ELIGIBILITY` 與領域 handler；Java 只比較保存日期，不作醫療資格判斷。
- 圖片缺少最早日期時主動詢問；未取得明確日期與時間前不建立下一次行程。安全的捐血生命週期
  同步進 tag graph，醫療身分資料不建立標籤。
- 能力目錄新增 #335–#338；聚焦測試涵蓋日期約束、actor-scoped repository、圖片匯入、
  醫療安全回覆與 Registry 完整性；完整回歸 873 項通過、3 項依既有條件跳過。

### 20.18 Intent issue #122（2026-07-19）

- 圖片 structured output 新增 `PAINT_PRODUCT`，只抄產品、品牌、顏色與色號；圖片不能建立用途、
  推薦或不適事實。
- V46 新增 actor-isolated 七天 `product_observation_draft`，並擴充既有 `UserKnowledgeFact` 分類為
  `PRODUCT_USAGE`、`PRODUCT_RECOMMENDATION`、`PRODUCT_CAUTION`。
- 新增三種 `ProductExperienceIntentHandler` command。使用者選定用途後，才保存私有知識與
  tag graph LifeRecord；不適細節不放入 tag 節點。
- 加入油漆購物清單後，Java 會比對本人明確保存的警示並提醒核對，不阻擋購買、不作醫療診斷。
- 能力目錄新增 #339–#343；聚焦測試涵蓋圖片不推論、三種知識邊界、購物警示、prompt 長度、
  Registry 完整性、V46 migration 與同 workspace 不同 actor 的 RLS；完整回歸 880 項通過、
  3 項依既有條件跳過。

### 20.19 限期草稿保留政策（2026-07-19）

- V47 新增 actor-isolated 草稿預設與 binding；目前納管原本固定七天的銀行轉帳與產品觀察草稿。
- 初始預設保留 7 個台北曆日、到期日 20:00 提醒；第一次建立、每次建立與每次編輯都回顯期限、
  固定 24:00 刪除時刻及提醒時間。
- 預設與個別草稿都能分別控制保留期及提醒；兩項各自保有 `uses_default`，切回其中一項不影響另一項，
  修改預設也只同步仍繼承該預設的 binding。
- Java 與資料庫共同限制保留 1–30 日、提醒最晚 23:00；domain 另拒絕少於收到設定後 5 分鐘、
  過去或不早於刪除的提醒。任何預設批次重算只要有一筆失效即回滾。
- actor 背景工作依 binding 主動通知並在台北午夜刪除；完成草稿先移除 binding，之後不再提醒。
- 能力目錄新增 #344–#346；domain、對話、通知／刪除服務、Flyway V1–V47 與同 workspace 不同 actor
  RLS 已有測試。
- 完整回歸 890 項通過、3 項依既有條件跳過。

### 20.20 Intent issue #123：繳費通知與彈性當日提醒（2026-07-19）

- 圖片 structured output 新增 `PAYMENT_NOTICE`，與已付款的 `TAX_PAYMENT`／`BANK_TRANSFER` 明確分流；
  只擷取費用名稱、收款單位、期限與明確金額，不擷取帳號、條碼、QR code、驗證碼或個資。
- V48 新增 actor-isolated `payment_notice` 與 `flexible_day_task_plan`。繳費通知先保存為
  `PENDING_REMINDER`，使用者明講「到期前 N 天提醒」後 Java 才計算提醒日並建立 Task；全程不付款、
  不落消費紀錄，也不把通知誤稱為付款證明。
- 新增 `CREATE_FLEXIBLE_DAY_TASK`。只有日期、沒有固定鐘點的事項不建立全天行程；Java 從本人已確認
  行程找 08:00–23:00 第一個可用空檔作提醒，並清楚回顯這只是建議時點、不占滿行事曆。
- 提醒至少晚於系統收到設定 5 分鐘；Task 完成或取消後同步關閉 plan／notice，既有狀態守門保證
  佇列與 escalation 不再送出後續提醒。
- 原圖沿用私有媒體保存並新增「繳費通知」分類標籤。能力目錄新增 #347–#348；聚焦測試涵蓋
  五分鐘邊界、空檔選擇、中文提前天數、圖片不落帳、Handler 目錄與 V1–V48／actor RLS。
- 完整回歸 897 項通過、3 項依既有條件跳過。

### 20.21 Intent issue #124：最近日常繳費紀錄（2026-07-19）

- 新增唯讀 `ASK_PAYMENT_HISTORY`，不再把「最近有沒有繳費紀錄」誤導成必須先提供日期區間；預設最近
  10 筆，可明確指定 1–50 筆。
- 回覆只採用已保存的 `PriceRecord`，逐筆列出日期、項目、金額與商家／分類並由 Java 加總；尚未付款的
  `payment_notice` 草稿明確排除，不把通知、帳單或辨識結果宣稱成已付款。
- `ConsumptionTagCatalog` 新增 deterministic 繳費種類，新消費建立 `activity:payment`／`payment:<kind>`
  標籤，舊資料也能在查詢時套用相同分類，且有「咖啡」非費用的反例測試。
- 能力目錄新增 #349；Handler、提示詞上限、唯讀安全診斷、服務彙總及 API 流程共 93 項聚焦測試通過。
- 完整回歸 902 項通過、3 項依既有條件跳過。

### 20.22 情境 #163 與共用時間系統（2026-07-19）

- V49 新增條件式週期 `SKIP` 假日政策及 `SKIPPED` 稽核結果；官方國定假日未知時不跳過也不建行程，
  已確認為假日才跳過本次，補課等待老師另行提供且不自動建立。
- 原句「週三七點」不猜上午／晚上，並與缺少的課程時長一次澄清；後續同句補齊可承接 actor/channel
  對話上下文建立待啟用草稿，仍需明確啟用，不會降級成普通 WEEKLY 行程。
- 統一 deterministic 中文時段規則：凌晨／上午／早上為 AM，下午／晚上／黃昏／傍晚為 PM；中午
  11:30／12:30／1:00 固定為 11:30／12:30／13:00，並供主要口語時間解析器共用。
- V50 新增 actor-isolated 12／24 小時對話顯示偏好，預設 24 小時；只改人類可讀 message，不改 API
  或資料庫 ISO 時間欄位。能力目錄新增 #350–#351，V1–V50、兩輪 API 與 actor RLS 已通過聚焦測試。
- 完整回歸 934 項通過、3 項依既有條件跳過。

### 20.23 情境 #164 每月第 N 個星期週期（2026-07-19）

- 新增 `MONTHLY_NTH_WEEKDAY` 與純 Java `ScheduleRecurrenceCalculator`；下一場、日程投影、略過單次與
  rollover 共用同一規則，不以 LLM 或固定天數估算。
- 明確月週次語句先由對話服務處理。缺少時長時回問且零 mutation；下一句補齊後建立專用週期，
  不會退化成 `WEEKLY`。不存在的第五個星期幾不會移到錯誤日期，而是略過該月份。
- 能力目錄新增 #352；typed capability enum schema、兩輪 API、日期 calculator 與真實 DB rollover 均有回歸。
- 完整回歸 939 項通過、3 項依既有條件跳過；資料庫仍為 V50，無需 schema 變更。

### 20.24 情境 #165 單一條件場地（2026-07-19）

- V51 建立 actor-isolated 條件場地草稿，透過資料庫約束保存 `PENDING/RESOLVED/CANCELED`，並把最終
  schedule ID 與 decision task ID 留作稽核；同一草稿只能解析一次。
- 原句「明天六點」未帶時段時與缺少活動時長一次澄清。補齊後只排決策提醒，不先建立原定與備用
  兩個版本；明確說健身房有開或休館後才建立唯一行程並完成提醒 task。
- 只有既有 Place 才綁座標；未知泛稱保存選擇文字但不建立假地點。能力目錄新增 #353，三輪 API、
  domain 單選限制與同 workspace 不同 actor 的 V51 RLS 均有回歸。
- 完整回歸 942 項通過、3 項依既有條件跳過。

### 20.25 全域 12／24 小時輸出偏好（2026-07-19）

- 延伸 V50 既有 actor-isolated 偏好：聊天、REST message 與 LINE 回覆仍由 `IntentService` 統一處理；
  reminder、草稿到期、天氣警示等主動通知則在 transactional outbox claim 後、建立 delivery envelope
  前套用收件人偏好。
- `NotificationOutboxWorker` 改為逐 active actor 執行，repository 明確以 `target_user_id` 領取與回收 lease，
  避免同 workspace 的代理 actor 讀取或套用其他收件人的私有偏好；claim token、retry 與 payload 清除規則不變。
- 12 小時制輸出使用中文上午／下午；24 小時制與未設定維持 `HH:mm`。REST 結構化欄位與資料庫 ISO
  時間完全不改。聊天、Reminder LOG delivery、outbox fencing／RLS 與格式化共 29 項聚焦測試通過。
- 完整回歸 943 項通過、3 項依既有條件跳過；資料庫仍為 V51，沒有新增 migration。

### 20.26 圖片引用與電費歷程（2026-07-19）

- LINE 入站圖片在辨識完成後保存有限解析摘要；引用舊圖片時若原紀錄只有 `[圖片]`，可回查緊接的
  assistant 回覆作相容摘要。對話理解可使用摘要，但 deterministic 快速規則只看本次使用者原文，避免
  舊回覆中的「決定」或「草稿」錯誤搶走「我什麼時候買的」等問題。
- V52 新增 actor-isolated `utility_bill_record`。多月份電費畫面分類為 `UTILITY_BILL_HISTORY`，不是
  `PAYMENT_NOTICE`；圖片只暫存完整可見列，須由使用者確認用電地點後才成為正式歷程。
- 民國年月轉換、同地點同月份取代、指定月份查詢與年度加總都由 Java 執行。被截斷的金額保存為未知，
  查詢時明示「已知小計」而非以 0 計算或猜測完整總額。能力目錄新增 #354–#356。
- 聚焦單元／migration／actor RLS／LINE 端到端均通過。完整測試產生 954 項報告（0 failure、0 error、
  3 skipped）；所有報告完成後 Maven 外層程序超過執行工具 15 分鐘上限，未取得最終 `BUILD SUCCESS`。

### 20.27 Intent issue #125–#132（2026-07-19）

- #125、#128–#130：LINE 圖片引用摘要與 deterministic 購買查詢在限期草稿前處理；Microsoft 可由
  item、merchant、organization 或既有標籤找到實際 Windows 購買日期、商家與金額，全程唯讀。
- #126：V52 電費歷程先暫存圖片明載列並追問用電地點；確認後可查上月、指定年月、民國年度各月與
  已知金額合計，被截斷的金額不算 0、不猜完整總額。
- #127、#132：短句「你沒聽懂」及明確引用錯誤回覆的長篇更正由 Java feedback boundary 優先攔截，
  不再被限期草稿或舊活動草稿消耗，也不異動行程／待辦。
- #131：V53 新增 actor-isolated 泛用 `object_annotation`，並以 `OBJECT_ANNOTATION` tag binding 連結
  任意使用者標籤；商品圖片不再要求用途／推薦／不適三選一。只有使用者明講才連到購物提醒，舊
  `PRODUCT_CAUTION` 僅做相容讀取。新增 `RECORD_PRODUCT_ANNOTATION` 與能力目錄 #358。
- 聚焦單元、LINE 端到端、V1–V53 migration 與同 workspace 不同 actor RLS 均通過；完整回歸
  963 項，0 failure、0 error、3 skipped，取得 `BUILD SUCCESS`。

### 20.28 Intent issue #143：場館參觀資訊與未來到訪提示（2026-07-20）

- 從 LINE 原圖 #16 還原「台灣昆蟲館 B2 水生動物展示區不開放自由參觀、須預約、10 人成團」案例；
  此類告示不是固定日期活動海報，不建立行程，也不宣稱已掃描 QR code 或完成預約。
- V54 新增 actor-isolated `venue_visit_information`。圖片只抄明確展示區、參觀限制、預約與成團人數；
  圖片未印場館名稱時停在 `PENDING_VENUE` 並追問，禁止從背景或裝潢猜地點。文字確認後才成為
  `ACTIVE` 私有知識，並同步寫入 LifeRecord／tag graph。
- 新增 `RECORD_VENUE_VISIT_INFO` 與唯讀 `ASK_VENUE_VISIT_INFO`；建立標題或地點符合的未來行程時，
  Java 會把本人保存的參觀提示附在回覆中，並要求預約前確認場館最新公告。
- 未知圖片回覆改為請使用者說明圖片種類與要處理的資訊，不再武斷宣稱「不是收據或旅行行程表」。
- 同一案例暴露的 `EventIntakeService` nullable `Integer` 三元運算拆箱 NPE 已修正；缺提醒分鐘時保留
  未知並繼續追問，不再讓 LINE 文字訊息 500 且無回覆。
- 能力目錄新增 #367–#368；36 項聚焦測試及 V1–V54 migration／actor RLS 整合測試通過。

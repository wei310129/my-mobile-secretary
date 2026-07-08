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

## 7. Phase 0：後端基礎工程

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

## 8. Phase 1A：任務與地點模型

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

## 9. Phase 1B：位置事件與 geofence 命中

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

## 10. Phase 1C：提醒引擎與通知替代方案

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

## 11. Phase 1D：Redis 延遲提醒與升級提醒

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

需要你決策的情況：

- 升級提醒間隔。建議 Phase 1 先用 15 分鐘。
- 最多升級次數。建議 Phase 1 先用 3 次。

## 12. Phase 1E：本機真實使用測試

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

## 13. Phase 2：規劃引擎與外部資料

預估：4-6 週。

目標：從「到地點提醒」進化為「根據時間、天氣、交通、營業時間判斷是否適合提醒」。

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

測試要求：

1. 每個 external client 都要測 timeout、非 2xx、格式錯誤。
2. planner 每個規則要獨立單元測試。
3. 多個規則組合要有 integration-style test。
4. cache hit/miss 要測。

需要你決策的情況：

- 外部 API 金鑰準備狀態。
- 第一個外部整合要選氣象署、TDX 還是 Google Places。
- planner v1 的第一個真實情境要選採買、接送、還是舊衣回收。

## 14. Phase 3：AI 與輸入摩擦降低

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

原則：

- LLM 只做理解與表達。
- 排程、地理、時間窗、可靠度判斷仍由 Java 規則引擎處理。
- LLM response 一律驗證 schema。
- LLM 失敗不能讓提醒核心不可用。

需要你決策的情況：

- LLM provider 選擇。
- 是否允許多模態模型處理收據。
- LINE bot 是否在 Phase 3 必做。

## 15. Phase 4：學習導向技術升級

預估：持續演進。

內容：

1. Spring Events 換 Redis Streams。
2. 再視需求導入 Kafka。
3. 拆 1-2 個模組練 Spring Cloud。
4. K8s 部署。
5. 事件重播與習慣分析。

原則：

- 只有當 Phase 1-3 的核心體驗穩定後才升級基礎設施。
- Kafka、Spring Cloud、K8s 是學習與擴展目標，不是 MVP 前置條件。

## 16. 決策通知規則

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

## 17. 建議下一步

第一個實作 PR 建議只做 Phase 0：

1. 補 Maven dependencies。
2. 建 Docker Compose。
3. 建 application profiles。
4. 建 package skeleton。
5. 建全域錯誤格式。
6. 建第一批 context load 與 health check 測試。

這個 PR 不碰業務模型，先讓工程地基站穩。Phase 0 通過後，再進 Phase 1A 的任務與地點模型。

# Intent 執行層架構評估與重構結果

## 現況判斷

重構前，`IntentCommand.Type` 共 109 種；`IntentService` 超過 1,500 行並同時負責解析、fallback、context、trace、安全邊界與所有領域執行，`LifestyleIntentService` 也超過 1,100 行、17 個依賴及約 60 個 case。兩者確實存在 God Service 與大型 switch 問題。

本專案適合使用「領域 Handler + immutable Registry」，但不適合每個 Type 建一個類別。查詢與修改的安全語意不同，因此 task、schedule 分成 query/mutation Handler；其他能力按依賴內聚度分組。

## 完成後架構

`IntentService` 保留快速規則、`IntentInterpreter`、LLM failure fallback、空指令防護、script/date/safety policy、`VagueTimeGuard`、單筆與批次隔離、mutation boundary、`IntentIssue`、conversation context 與 decision trace。驗證後的可執行 command 一律交給 `IntentHandlerRegistry`。

`IntentHandlerRegistry` 在建構時建立不可變的 `Type -> Handler` 映射；重複 Type 立即使啟動失敗，未註冊 Type 會明確指出缺少的 enum。完整性測試確認 108 個可執行 Type 均唯一註冊；`UNKNOWN` 刻意由最外層回問。

| Handler | Intent Type 數量 | 責任 |
|---|---:|---|
| `ActivityIntentHandler` | 3 | 最近活動、行程結果回報、產品回饋 |
| `ConversationIntentHandler` | 4 | 活動問答、失敗說明、社交回覆 |
| `ContextIntentHandler` | 5 | 上一筆 task/schedule context 操作 |
| `PlaceIntentHandler` | 11 | 地點、別名、任務地點、geofence、附近建議 |
| `PlannerIntentHandler` | 9 | 空檔、路線、天氣、交通、可行性與緩衝 |
| `ReminderIntentHandler` | 7 | 行程提醒與提醒偏好 |
| `ScheduleMutationIntentHandler` | 7 | 建立、相對建立、改期、週期、縮放與批次取消 |
| `ScheduleQueryIntentHandler` | 13 | 行程／agenda 清單、空檔、衝突與洞察 |
| `ShoppingIntentHandler` | 23 | shopping、inventory、item knowledge 全領域 |
| `TaskMutationIntentHandler` | 9 | 建立、完成、取消、改期、更新與週期操作 |
| `TaskQueryIntentHandler` | 9 | 待辦清單、明細、進度、分組與洞察 |
| `TravelIntentHandler` | 8 | 旅行規劃、行李偏好、行程草稿與餐廳引導 |

`LifestyleIntentService` 已不再分派或執行 Intent，只保留一個無依賴的 deprecated source-compatibility shim，供既有日期範圍 static helper 測試使用。後續可在測試完成遷移時刪除。

## 行為與安全邊界

- Handler 不呼叫 LLM、不注入 repository、`ApplicationContext` 或 Registry 作為 service locator。
- destructive operation 仍由既有 application service 與 Java 配對／範圍規則執行。
- `IntentService` 的 LLM fallback、多 command 單項隔離與 mutation boundary 未搬進 Handler。
- 從舊 `LifestyleIntentService` 搬出的 `IllegalArgumentException` 仍轉為原有 clarification；原本直接位於 `IntentService` 的核心流程則保留外層錯誤處理語意。
- `TaskAdviceClassificationTest` 原本直接依賴 `IntentService` package-private helper，已改測新的責任擁有者 `TaskQueryIntentHandler`。

## Spring Modulith 評估

頂層 package 已接近模組邊界，但 `intent` 是跨 task、schedule、geo、knowledge、planner、reminder 的 orchestration，現況仍有跨領域 application class 引用。現在加入 Modulith 嚴格驗證會同時迫使大量 package/named-interface 遷移，風險高於本次收益。

本次不加入依賴。建議後續以獨立工作先加入只讀架構驗證與違規基準，再逐步定義 named interfaces；不要與 Handler 重構綁在同一批變更。

## AI 執行模式

目前是 `Structured Output + Deterministic Command Execution`，不是 Spring AI Tool Calling。未來可選擇性評估 read-only Tool Calling；mutation 與 destructive operation 仍必須由 Java 安全邊界核准與執行。本次未導入 ADK、MCP、Multi-Agent、微服務或資料庫 schema 變更。

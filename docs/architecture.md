# 分身秘書 App — 技術架構與開發規劃

> 用途：本文件是「分身秘書 App」專案的核心背景知識，濃縮了完整的需求分析、技術選型、系統架構與開發路線圖。上傳至 Claude 專案知識庫後，專案內所有新對話都能以此為共同前提。
>
> 建議搭配的專案自訂指示（custom instructions）：「以繁體中文回覆。使用者是三年經驗的 Java 工程師，本專案後端採 Spring Boot 3.5 + Java 21，不要提供舊版寫法。講解請由淺入深、白話。所有設計決策以本知識庫的 architecture.md 為準。」

---

## 1. 產品定位

一句話：**情境感知的個人排程與提醒系統**——不是待辦清單，而是能主動在「對的時間、對的地點」找上使用者的分身秘書。

- 目標使用者：開發者本人。注意力容易分散、常忘記待辦，需要系統**主動提醒**，而不是自己記得打開 app。
- 核心價值排序：**提醒的可靠度 > 提醒的聰明度**。該響的那一次沒響，信任就毀了。
- 決策依據的七種資料：位置 × 時間 × 天氣 × 交通 × 行事曆 × 個人習慣 × 價格歷史。
- 平台策略：iPhone 先行，未來擴充 Android（架構上以「後端持有全部邏輯」確保 Android 版只需重寫薄 UI 層）。

典型需求場景（原始需求的代表案例）：
- 採買清單（白蘿蔔、排骨、Tabasco）要依「哪裡買得到、營業時間、時段風險（排骨 10 點後常買不到）、天氣（下雨不便拿、高溫蛤蜊不新鮮）、負重、冷藏鏈、順路與行程空檔」給出可行方案。
- 行事曆固定行程（週六 10–12 送小孩上英文課）之間的空檔，自動辨識為採買時機，並驗算「採買＋回家上五樓放冰箱＋休息＋重新出發接小孩」是否來得及。
- 交辦事項追蹤閉環：提醒「跟老師說學費已繳」後追蹤是否回報完成；「帶 12 色蠟筆」要先確認家中有無、追蹤購買、前一天確認取貨可行、出發前 10–15 分鐘最後提醒。
- 相依待辦閉環：後續待辦以 `deferred_task` 保存，只有前項待辦經 Java 狀態機確認完成後才成為正式待辦並排入提醒；LLM 不得自行宣告前項完成或提早啟用。
- LINE 訊息（老師說停課）轉傳給系統後自動整合行程。
- 拍收據照片回報採買結果，累積價格歷史並比對漲價、比較哪家划算。
- 順路型任務：「找『我家』附近的舊衣回收箱，用最省時省力的時機丟」。

## 2. 開發者背景與偏好

- 三年 Java 後端經驗；慣用 Spring Boot 3.x、Java 17 以上（本專案定案 **Spring Boot 3.5 + Java 21 LTS**）。
- 學習目標：Kafka、Spring Cloud、K8s、Redis、演算法、多執行緒——架構刻意保留這些技術的練習空間，但不讓學習目標拖垮產品進度。
- 主力開發機為 Windows。iOS 開發必須另備 macOS + Xcode（建議二手 M 系列 Mac mini），並加入 Apple Developer Program（99 美元/年，APNs 推播必要）。
- 溝通偏好：繁體中文、由淺入深、白話講解。

## 3. 技術選型結論

| 層面 | 選擇 | 理由 |
|---|---|---|
| 手機端 | SwiftUI（原生 iOS） | geofence 背景喚醒、EventKit、Live Activities 是命脈，只有原生最可靠 |
| 後端 | Spring Boot 3.5 + Java 21，模組化單體 | 用最強技能寫大腦；Android 版免重寫邏輯 |
| 主資料庫 | PostgreSQL 16 + PostGIS（pgvector 規劃中） | 關聯資料、地理查詢、未來 AI 向量記憶一次滿足 |
| 快取／佇列 | Redis 7 | 外部 API 快取、GEO 查詢、延遲提醒佇列 |
| AI 層 | Spring AI + LLM API（Structured Output） | LLM 只負責理解與表達，Java 負責驗證、計算與確定性執行 |
| 事件流 | Spring Events → Redis Streams → Kafka | 由簡入繁；Kafka 留待 Phase 4 學習導向導入 |
| 推播 | APNs + Live Activities | 鎖屏即時倒數提醒，符合 ADD 使用情境 |

手機端框架比較摘要：SwiftUI 首選（系統代管 geofence，app 被殺仍可喚醒）；Flutter 次選（背景定位靠第三方付費插件、原生能力隔一層）；React Native 不建議（背景問題更明顯）；Kotlin Multiplatform 過度設計（商業邏輯都在後端，共用價值低）。

## 4. 核心架構原則：薄手機、厚後端

手機端只做三件事：**感測**（GPS／geofence／拍照／語音）、**顯示**（UI／行事曆／Live Activities 倒數）、**通知**（接收 APNs）。所有判斷、規劃、記憶都在後端。

理由：iOS 背景執行限制極嚴，邏輯放手機上 app 被殺就失效；未來 Android 版只需重寫薄殼；LLM 與外部 API 整合天生屬於伺服器端。

```
┌─────────────────────────────────────────────┐
│  iPhone（SwiftUI 薄殼）                       │
│  GPS/geofence 感測・EventKit 行事曆・推播顯示  │
└──────────┬───────────────────▲──────────────┘
    位置事件・回報          APNs 提醒推播
┌──────────▼───────────────────┴──────────────┐
│  Spring Boot 3.5 後端大腦（模組化單體）        │
│  ┌──────────┐ ┌──────────┐                  │
│  │ 意圖理解  │ │ 規劃引擎  │  intent/planner  │
│  │ SpringAI │ │ 可行性演算 │                  │
│  ├──────────┤ ├──────────┤                  │
│  │ 提醒引擎  │ │ 知識與記憶 │ reminder/        │
│  │ 狀態機   │ │ 習慣/價格  │ knowledge        │
│  └──────────┘ └──────────┘                  │
└───────┬──────────┬──────────┬───────────────┘
        ▼          ▼          ▼
  PostgreSQL     Redis      外部 API
  PostGIS      快取/GEO/    LLM・TDX 交通・
  pgvector     延遲佇列     氣象署・Places・LINE
```

## 5. 手機端（SwiftUI）關鍵系統框架

- Core Location：region monitoring（geofence，上限 20 區域）、significant location change（省電粗定位喚醒）、iOS 17+ 可評估 CLMonitor。
- EventKit：讀寫 iPhone 內建行事曆（固定行程錨點來源）。
- UserNotifications：本地與遠端通知，申請 Time-Sensitive 等級。
- ActivityKit：Live Activities 動態島倒數（例：「11:40 前要出發接小孩」）。
- App Intents：Siri 捷徑快速輸入待辦，把輸入摩擦降到最低。

## 6. 後端：Spring Boot 3.5 / Java 21 模組化單體

不先上 Spring Cloud 微服務——單人開發會把時間耗在基礎設施。採模組化單體（modular monolith）：一個 Spring Boot 專案，套件邊界切乾淨，模組間只透過介面與事件溝通，未來可直接拆出微服務。

模組切分（2026-07-17 現況，全部已存在）：
- `api`：REST 入口（task／place／location／reminder／schedule／item／weather／intent／LINE／internal feed）
- `account`：帳號、workspace 多租戶隔離（PostgreSQL RLS）、security audit、idempotency
- `family`：家人通知理解、隱私家人身分檔案
- `geo`：位置事件處理、geofence 命中、距離半徑判斷
- `intent`：Spring AI（Anthropic）意圖理解（structured output）＋ typed capability registry
- `utility`：電費歷程圖片的待確認匯入、actor／用電地點隔離與 deterministic 年月查詢；民國年轉換及加總由 Java 執行
- `knowledge`：物品、庫存、價格歷史
- `planner`：可行性與排程演算法、空檔／順路建議、交通估算、天氣規則
- `reminder`：提醒排程與任務狀態機、debounce、升級催促
- `schedule`：行程 source of truth、週期行程、pending 池、行程結果追蹤
- `travel`：旅行規劃引導、行李清單、行程表圖片草稿
- `integration`：TDX／氣象署／Google Places／LINE／通知（log、Windows Toast、outbox）轉接層
- `shared`：error／time／validation／security／observability

另有 `internal/ai-dispatcher`：同 repo 內**完全隔離的獨立 Spring Boot 應用**（自有 Maven build、PostgreSQL、Flyway），控制 Codex 開發 agent 的啟動與生命週期。它是開發自動化基礎設施，不是產品 runtime 的一部分；隔離保證與設計見 `internal/ai-dispatcher/README.md` 與 `DESIGN.md`。

Java 21 重點：`spring.threads.virtual.enabled=true` 啟用虛擬執行緒；規劃引擎每次評估需平行呼叫天氣、交通、店家等多個外部 API 再彙總，是多執行緒的天然練習題。

部署：Docker Compose + 單台 VPS 起步；K8s 留待 Phase 4（學習目的，非必要）。

## 7. 資料層

PostgreSQL 16（主資料庫）：
- PostGIS：空間查詢，如「我家 800 公尺內的舊衣回收箱」（回收點可匯入政府開放資料）。
- pgvector（規劃中，尚未啟用）：對話與事件的語意向量，供 AI 記憶檢索。
- 核心資料表概念：店家（座標／營業時間／販售品項）、物品（冷藏需求／重量估計／可購通路）、習慣規則（菜市場只去週末 8–12、排骨 10 點後風險高、回家爬五樓需 10 分鐘緩衝）、價格歷史、任務與狀態、事件紀錄。

Redis 7 的三個角色：
1. 外部 API 結果快取（天氣／交通／地點，省費用又快）。
2. GEO 指令做熱點附近查詢。
3. sorted set 延遲提醒佇列（排程器撈出到點提醒發推播）。

## 8. AI 五層設計（最重要的原則：LLM 不做排程計算）

直接叫 LLM 排行程會得到「看起來合理但經不起驗算」的答案。正確分層：

1. **意圖理解（LLM）**：把自然語言（打字／Siri 語音／LINE 轉傳／收據照片）轉成結構化 JSON。目前使用 Spring AI Structured Output；不是 Tool Calling，模型不直接執行業務操作。
2. **知識庫（PostgreSQL，非 LLM）**：結構化事實——哪裡買得到、營業時間、冷藏需求、重量、習慣規則、價格歷史。
3. **規劃引擎（確定性 Java 程式）**：時間窗檢查、交通時間、負重上限、冷藏鏈、行程衝突、順路判斷。本質是「帶時間窗的排程問題」：先以貪婪法＋規則過濾做堪用版，再研究 interval scheduling、帶時間窗路徑規劃優化。
4. **表達層（LLM）**：把計算結果轉成自然貼心的推播文字。
5. **記憶層**：結構化 profile 為主，pgvector 語意檢索為輔（撈模糊記憶餵給 LLM 當上下文；pgvector 部分尚未啟用）。

LLM 成本控制原則：位置事件一天可能數十次，規則引擎先過濾（快且免費），只在需要「理解語言」或「生成語言」時呼叫模型，搭配快取，目標每月數百元台幣量級。

## 9. 事件驅動與動態行事曆

事件源：GPS 進出區域、定時排程、行事曆變動、天氣變化、使用者回報。**任一事件都觸發規劃引擎重新評估**——這就是「動態行事曆」：EventKit 的固定行程是錨點，彈性任務池由引擎即時塞進空檔並隨事件重排。

事件匯流排演進：Phase 1 用 Spring Events（單體內、零基礎設施）→ Phase 2–3 換 Redis Streams（輕量持久、consumer group）→ Phase 4 換 Kafka（學習導向；事件重播可重跑歷史位置事件做習慣分析）。

## 10. 提醒閉環（任務狀態機）

秘書與鬧鐘的差別在「追蹤到底」：

```
CREATED → SCHEDULED → REMINDED → 等待回報 ─→ CONFIRMED
                                    │
                                    └→ 未回應 → ESCALATED（升級再提醒）
```

- 前置依賴任務：例「週六帶 12 色蠟筆」＝ 確認家中有無 → 沒有則下單並追蹤 → 前一天確認取貨可行（店到店 11 點才開門則當天來不及領）→ 出發前 10–15 分鐘最後提醒。
- 回報方式：文字，或拍收據照片 → 多模態 LLM 解析品項與金額 → 寫入價格歷史 → 自動比對（「排骨半斤 95，比上次貴 10 元」）與跨店比價。

## 11. 外部整合（台灣在地）

- TDX 運輸資料流通服務（政府、免費）：公車／捷運路線與即時到站 →「最晚幾點出門才趕得上」的判斷來源。
- 中央氣象署開放資料平台（免費）：降雨、高溫規則（下大雨提醒少買、高溫提醒蛤蜊早點買）。
- Google Places API：店家營業時間、附近搜尋（控制在免費額度內，靠 Redis 快取壓用量）。
- LINE Messaging API：建 LINE 官方帳號 bot，使用者將訊息「轉傳」給它，webhook 進後端解析。（LINE 沒有讓第三方直接讀訊息的 API，轉傳是正規解法。）
- APNs：遠端推播（需 Apple Developer Program）。
- 未來選項：財政部電子發票 API（手機條碼載具）自動取得消費明細，取代拍收據。

## 12. 關鍵技術難題與對策

1. **iOS geofence 上限 20 個區域**：後端依目前位置動態挑選最相關的 15–20 個地點下發註冊；significant location change 偵測到大幅移動時重新換批。這是全系統最核心的工程設計。
2. **電池**：禁止連續 GPS 追蹤。只用 geofence 進出事件與 significant location change 這類系統級低耗電機制，手機採事件式回報。
3. **LLM 成本**：規則先過濾＋快取（見第 8 節）。
4. **隱私**：只上傳「進入／離開區域」事件、不上傳連續軌跡；資料庫加密；伺服器自架自管。

## 13. 開發路線圖

| 階段 | 期程 | 內容 | 對應學習目標 |
|---|---|---|---|
| Phase 0 | 2–3 週 | Mac + Xcode 環境、Swift 速成、Apple Developer 註冊、TDX／氣象署／LINE／Google 金鑰申請 | Swift 基礎 |
| Phase 1 MVP | 6–8 週 | 待辦管理、EventKit 行事曆同步、手動建立地點、geofence 到點提醒、APNs 推播；Docker Compose 部署單台 VPS。驗收標準：自己天天在用 | Spring Boot 3.5、JPA + PostGIS、Redis 基礎 |
| Phase 2 | 4–6 週 | Spring AI 意圖理解、店家／物品知識庫、天氣＋交通整合、規劃引擎 v1 | 虛擬執行緒與多執行緒（平行呼叫外部 API）、時間窗排程演算法 |
| Phase 3 | 4–6 週 | LINE bot、收據多模態解析、價格歷史與比價、任務狀態機閉環、Live Activities | 多模態 LLM 應用、狀態機設計 |
| Phase 4 | 持續 | 事件流換 Kafka、拆 1–2 個模組練 Spring Cloud、K8s 部署、習慣自動學習 | Kafka、Spring Cloud、K8s |

> **進度現況（2026-07-17）**：本表是原始路線圖。因無 Mac，iOS／APNs／EventKit／Live Activities 全線未動，改以「API 模擬手機事件 + LINE Bot 互動」推進後端：Phase 1（後端版）與 Phase 2 已完成，Phase 3 進行中。逐項對照見 development-plan.md §20。

## 14. 風險與指導原則

- **最大風險是範圍失控，不是選錯技術。** 完整願景約等於小團隊一年的量；Phase 1 必須砍到兩個月內能天天使用。
- **可靠度優先**：樸素的 geofence 提醒是地基，打磨到接近百分之百可靠，AI 是之後疊上去的。
- 固定成本估算：VPS 每月數百元台幣、LLM API 每月數百元台幣量級（做好過濾與快取）、Google API 控制在免費額度、Apple Developer 99 美元/年。
- 每個 Phase 結束以「真實使用一週」驗收，再決定下一步。

## 15. 端到端範例（採買情境）

使用者說「要買白蘿蔔、排骨、Tabasco」→ 意圖理解拆成三個 item → 知識庫查出：白蘿蔔／排骨菜市場有（10 點後排骨風險高）、Tabasco 只有全聯／家樂福、排骨需冷藏、總重估約 2 公斤 → 週六 10:05 GPS 進入英文教室 geofence 觸發評估：行事曆 10–12 空檔、氣象署回報無雨、最近全聯步行 8 分鐘 → 規劃引擎驗算：採買 20 分＋回家 15 分＋上五樓放冰箱休息 10 分，11:40 前重新出門接小孩，可行 → 表達層推播：「現在去 XX 全聯可一次買齊三樣（菜市場此時排骨大概沒了），11:35 前離開賣場就趕得上接小孩」→ 使用者拍收據回報 → 解析品項金額寫入價格歷史 → 回覆「排骨半斤 95，比上次貴 10 元」。

## 16. 架構補充（2026-07-10）

因應需求擴充（詳見 development-plan.md §19），以下調整與新增：

1. **行程 source of truth 移至後端**：因可行性把關與家庭共享需求，後端新 `schedule` 模組的 ScheduleItem 為行程主資料；EventKit 改定位為（未來的）同步來源之一，不再是唯一錨點。
2. **可行性把關流程（要可行才放行）**：新行程必須通過規劃引擎可行性檢查（時間衝突、位置與交通可達性）才 CONFIRMED；不可行 → 警告 + 使用者決定（改時間 / 安排回程交通 / 轉 PENDING）。Phase 2 先以直線距離粗估，TDX 接上後變準。
3. **Pending 池**：未定案事項的暫存池（§9 彈性任務池的具體化）；系統於空閒時段透過通知通道主動詢問是否安排。
4. **意外追蹤與緩衝學習**：行程結束後追蹤實際結果（會議超時 / 交通意外 / 上下班尖峰），依行程類型累積緩衝規則，排程時自動加上；長期由 Phase 4 習慣學習自動化。
5. **家庭共享（Phase 5，遠期）**：行程共享/共編、變更後全員重新檢視（ACK 機制）、替代安排註記。多使用者（帳號、認證、全表資料歸屬）為該階段前置工程。位置事件永遠不共享。（前置工程已於 2026-07 以 workspace 形式提前落地，見 §17。）

## 17. 架構現況補記（2026-07-17）

長期由 Codex 並行開發後的架構盤點，以下為實際已落地、且與前文原始規劃有出入的部分：

1. **workspace 多租戶基礎已提前落地**：`account` 模組建立帳號、workspace 與 membership，並以 PostgreSQL Row-Level Security 做資料隔離的第二道防線（runtime role 強制 `NOBYPASSRLS`，transaction-local scope 變數）。§16 第 5 點的「新資料表不預加 user_id」已被取代——新表一律掛 `workspace_id` 並遵循 RLS pattern。家庭「共享/共編」功能本身仍在 Phase 5。詳見 `docs/security-deployment.md`。
2. **對話與 actor 隔離**：對話歷史按 actor 隔離；LINE 有 owner guard 與簽章驗證；家人身分檔案（`family` 模組）可識別但不外洩至 LLM。
3. **意圖執行架構**：free-form intent command（114 種）之上疊了 typed capability registry（目前 shadow 驗證模式）；113 個可執行 Type 已全部由領域 `IntentHandler` 經 immutable `IntentHandlerRegistry` 做 deterministic command execution，`UNKNOWN` 留在最外層回問。未來可選擇性評估唯讀 Tool Calling，但 mutation／destructive operation 仍須由 Java 安全邊界核准與執行。LLM provider 定案 Spring AI + Anthropic；prompt injection 防護政策見 `docs/security-deployment.md`。
4. **通知通道現況**：APNs 尚未存在；實際通道為 server log、Windows Toast 與 LINE，經 notification outbox pattern 送出。
5. **事件匯流排現況**：仍為 Spring Events（§9 原規劃 Phase 2–3 換 Redis Streams 未執行，順延 Phase 4 評估）；Redis 用於外部 API 快取與延遲提醒佇列。
6. **開發自動化**：`internal/ai-dispatcher` 獨立應用（見 §6 末段）控制 Codex 開發 agent，與產品 runtime 完全隔離。

## 18. 消費紀錄與生活標籤（2026-07-18）

既有 `price_record` 不另建重複帳本，而是演進成可稽核的消費明細：保留單價，另存數量、
實付總額、受控消費分類與語意標籤。收據 structured output 只擷取欄位；Java 驗證數量與價格、
計算總額並以確定性 `ConsumptionTagCatalog` 分類，未知項目維持 `UNKNOWN`，不得交由 LLM 猜測。

V38 起新增 actor-isolated semantic tag graph，以 PostgreSQL 關聯表實作節點、別名、typed edge
與 record binding；目前不導入 Neo4j。`IS_A` 可由父標籤向下查四層，例如
`節能補助 → 政府補助 → 補助`，其他 `RELATED_TO/PART_OF/ELIGIBLE_FOR/PROVIDED_BY`
關係可雙向探索。每一條 USER edge 都必須來自使用者明講，LLM 不得自行推論某補助由政府提供。

所有非開發回饋的使用者文字都由 `IntentService` 共同行程建立基本 `USER_UTTERANCE` LifeRecord，
至少綁定生活紀錄與實際 action。領域服務只發布已通過 Java 規則的生命週期事件；共同 recorder
統一將 task 建立／完成／取消、schedule 建立／放棄／取消／完成、reminder 實際觸發、place 建立、
shopping／inventory 異動、location exit 與 schedule outcome 寫入 tag graph。收據價格則在同一交易
建立商品、消費分類、店家節點與 binding。這使 LINE、REST 與背景排程共用同一紀錄邊界，而不是
只在 Intent handler 補寫紀錄。
醫療身分、診斷、證件與聯絡資料不因「萬物皆標籤」而放寬：只保留安全領域標籤與去識別標題。
後續新增 domain event 時必須同步接入 graph recorder，避免只寫主資料卻漏掉生活索引。

## 19. LINE 引用上下文與官方停班停課查核（2026-07-19）

LINE 的引用 webhook 只提供 `quotedMessageId`，平台不提供事後取回被引用文字的 API。V39 因此在
既有 90 天 LINE message log 保存外部 message ID 與 quoted message ID；精確引用優先回查同
workspace／actor 的本地紀錄，舊訊息或已過保存期才附最多六則近期對話。這段上下文只供理解，
原始新訊息仍獨立用於稽核、LifeRecord、issue 與 ConversationContext。活動草稿的日期缺口另由
Java 接受 `M/d` 短句，以台北時區當年度解析並回顯完整日期。

V40 將停班停課圖片保存為 actor-isolated `work_school_suspension_draft`。多模態模型只分類並抄錄
日期、縣市與狀態；`PENDING_CONFIRMATION` 不等於官方事實。使用者回「不用」即停止；明確同意時
立即查行政院人事行政總處頁面；10 分鐘未回則由逐 actor 背景工作查核，結果走既有 notification
outbox。只有官方頁同時符合日期、縣市與停班停課文字才標為 `OFFICIAL_CONFIRMED`；頁面對不上或
連線失敗必須分別保存為未證實／查核失敗，並附官方來源 URL。

## 20. 活動報名成功文件（2026-07-19）

活動宣傳與報名成功是不同信任強度。`EVENT_POSTER` 仍只建立待確認草稿；
`EVENT_REGISTRATION` 代表使用者已完成報名，structured output 只擷取活動名稱、明確日期時間、
地點與明確實付報名費。Java 先檢查欄位與既有同行程：日期及起訖時間完整時透過
`ScheduleService` 建立一次行程，重送不重複；欄位不完整時保存 `REGISTRATION` 草稿並只追問缺項。

只有圖片明列實付新台幣金額時，才透過 `PriceRecordService` 另存「活動報名」消費並建立消費標籤；
免費、金額不明或只有原價時不建立零元／推測支出。LLM 不直接寫行程或消費資料，也不能跳過
`ScheduleService` 的衝突與可行性規則。

## 21. 私有原始媒體與 App 檔案管理（2026-07-19）

V41 新增 actor-isolated `stored_media`，只保存來源、檔名、可信 MIME、大小、SHA-256、不可猜的
storage key 與生命週期 metadata；原始 bytes 透過 `MediaObjectStorage` 保存，第一階段使用私有本機目錄，
API 不暴露磁碟路徑，之後可替換成 S3-compatible object storage。LINE 圖片與 App 上傳都採「先保存原檔，
再進 structured-output 辨識」，即使模型解析失敗，原始資料仍可由本人後續查看。

圖片是否可解讀以 magic bytes 判斷，不信任副檔名或 request Content-Type。PDF 與 OOXML
Word／PowerPoint／Excel 只驗證檔頭並當 opaque document 保存，不解壓、不開啟、不送進 LLM。
App 使用 actor-authenticated `/api/media` 上傳、列表與 content URL；下載一律 `nosniff`、private/no-store。
聊天的 `ASK_STORED_MEDIA` 只能列本人原檔連結，不能刪除。

每次上傳、辨識分類與刪除也進入既有 universal tag graph：media 本體綁定「原始檔案」、圖片／文件、
來源與受控分類標籤，生命週期另留去識別 LifeRecord。原檔名不直接轉成生活標籤，避免病歷、證件或
聯絡資訊因「萬物皆標籤」外洩。

刪除只開放 App 檔案管理的 `DELETE /api/media/{id}`。原始 bytes 在 transaction commit 後清除，metadata
保留 `DELETED/deletedAt` 作稽核，但不再出現在清單、不可下載且不計配額。單檔 15MB 是安全保險絲；
每 actor 方案配額已有檢查邊界，但預設 `0B`（未啟用），免費／付費容量待產品方案拍板，不由開發階段猜定。

## 22. 圖片文件的名片、發票與繳稅紀錄（2026-07-19）

圖片仍先進 V41 原始媒體保存，再由單次 structured output 分類與抄錄。`BUSINESS_CARD` 只允許抄錄
名片明確可讀的姓名／顯示名稱、公司、專業、電話、Email 與地址；Java 寫入 V42 actor-isolated
`external_contact`。電話或 Email 優先作去重鍵，重送只補既有空欄位。回覆不完整重述聯絡細節，
但本人可用純讀取 `ASK_CONTACT` 依姓名、公司或專業查詢。名片、聯絡人、專業與組織綁入既有
tag graph；資料仍受 workspace + actor RLS，不會混入家庭成員模型。

發票維持 `RECEIPT`：只有可核對品項、正數價格與數量才透過 `PriceRecordService` 建消費紀錄。
`TAX_PAYMENT` 只代表明確已繳、扣款成功或收訖證明；Java 必須同時取得實際繳納日期、稅目與
實繳金額才落帳，並以 `TAX` 分類供消費查詢。尚未繳納的稅單、試算單、繳費通知或截止日提醒
不得偽裝成已繳紀錄；其中有明確期限的繳費通知改由 §28 的獨立提醒流程處理。

## 23. 銀行轉帳隱碼與消費草稿（2026-07-19）

`BANK_TRANSFER` structured output 只抄錄銀行畫面上的收款人顯示值、實際交易日期、用途與轉帳金額。
Java 以固定遮罩規則辨識 `o/O/0/X/○/●/*/＊/Ｘ` 等銀行隱碼；含隱碼名稱不得直接成為 merchant，
也不允許 LLM 猜完整公司名。V43 將可核對的日期、金額與用途保存成 actor-isolated 的
`bank_transfer_draft`，只追問完整收款公司名稱。

使用者後續明確提供以公司／商行／行號／工作室／診所／醫院等結尾的完整名稱時，
`BankTransferService` 在 Java 內移除固定前導語、重新檢查沒有遮罩、取本人最新未過期草稿，
再透過 `PriceRecordService` 建立一次消費並將草稿標成 `COMPLETED`。圖片重送與文字補名都沿用
既有 LINE idempotency／actor context；其他文字不會消耗草稿。訂金、預付款、轉帳與匯款在沒有
更具體分類時歸 `OTHER`，仍保留完整 merchant/organization tag 供查詢。

## 24. 學校菜單結構化索引（2026-07-19）

`SCHOOL_MENU` 圖片先保存原圖，再只抄學校／幼兒園名稱、完整日期、餐別與餐點；學生姓名、班級、
座號與家長聯絡資料不得擷取。V44 `school_meal` 以 workspace + actor RLS 隔離，按學校、日期與餐別
去重更新。`ASK_SCHOOL_MEAL` 是唯讀 handler，可查指定日早餐，或在指定週內依「牛奶」等品項找日期。
每筆餐點同步綁定學校、餐別與品項 tag，不由 LLM 推測未印出的餐點或日期。

## 25. 捐血紀錄與日期門檻（2026-07-19）

`BLOOD_DONATION_RECORD` 圖片先保存原圖，structured output 只抄明確捐血日期、地點與圖片明載的
下次最早可捐日期；姓名、證號、捐血人編號、血型、檢驗結果與健康問卷都不得擷取。V45
`blood_donation_record` 採 workspace + actor RLS，且資料庫約束下次最早日期不得早於捐血日。

文字能力分為記錄、補日期與查詢三種 handler command。Java 只比較使用者或圖片已明確保存的
日期門檻，絕不依血量、性別或一般常識推算，也不把「已到日期」表述成醫療資格通過。圖片匯入後
可以詢問使用者是否安排下一次捐血，但只有明確提供日期與時間時才走既有 `CREATE_SCHEDULE`；
不得自動占用最早日期。tag graph 僅綁定安全的「捐血」「健康紀錄」與明確地點，不索引敏感醫療資料。

## 26. 商品圖片用途釐清與購買警示（2026-07-19）

`PAINT_PRODUCT` 圖片先保存原圖，structured output 只抄產品、品牌、顏色與色號；圖片本身不能證明
實際施工用途、朋友推薦、使用者不適或購物提醒。V46 `product_observation_draft` 以 workspace + actor
RLS 保存限期待註記草稿。

V53 將後續補述改成 actor-isolated `object_annotation`，以泛用 target type／ID 指向領域物件，任意註記
則透過既有 `semantic_tag_binding` 的 `OBJECT_ANNOTATION` target 綁定；不再為用途、推薦或警示持續增加
油漆專屬欄位。舊 `PRODUCT_CAUTION` 資料只保留相容讀取，新寫入一律走泛用註記。只有本人明確要求
「購買時提醒」才加上 `購物提醒` 標籤；購物清單由 Java 沿該標籤讀取註記並提示核對，不阻止購買、
不提供醫療診斷，也不把個人經驗套用給其他 actor。註記事件仍同步寫入安全的 tag graph LifeRecord，
詳細自由文字不轉成 tag 節點。

## 27. 限期草稿保留與到期提醒（2026-07-19）

V47 為目前採短期保留政策的銀行轉帳與產品觀察草稿建立 actor-isolated
`draft_retention_preference` 與 `draft_retention_binding`。初始預設為保留 7 個台北曆日、到期日
20:00 提醒；第一次建立時必須明確告知並請使用者確認。保留期可設 1–30 日，刪除時刻固定為
到期日 24:00，不提供自訂，避免每份草稿有不同清除批次。

過期設定與提醒設定各自保存 `uses_default_*`，可分別繼承預設、個別覆寫或個別切回預設。修改預設
只重算仍使用該項預設的草稿，不覆蓋另一項個別設定。每次建立或編輯都重算最後編輯日起算的期限，
並回顯有效保留天數、到期日、刪除時刻、提醒時間及來源。Java 強制提醒至少晚於收到設定 5 分鐘、
不得晚於 23:00、不得在過去且必須早於刪除時刻；預設調整若會使任何既有自訂組合失效，整筆交易
回滾並要求使用者先修正，不靜默搬動提醒。

`DraftRetentionWorker` 以 `WorkspaceBackgroundRunner.forEachActor` 執行，提醒先進既有
notification outbox，成功建立 durable envelope 後才標記已通知；午夜只刪除仍有 retention binding
的待完成草稿。草稿完成時 binding 立即移除，因此完成後不會再通知或被午夜工作重複處理。

## 28. 繳費通知與彈性當日任務（2026-07-19）

`PAYMENT_NOTICE` 與已付款的 `TAX_PAYMENT`／`BANK_TRANSFER` 分流。structured output 只抄費用名稱、
收款單位、明確期限與應繳台幣金額；完整帳號、卡號、繳款條碼、銷帳編號、QR code、驗證碼與個資
不進結構化結果。V48 `payment_notice` 保存「尚未付款」事實並等待使用者明講提前天數，不能因此
建立消費、宣稱已付款或執行付款。

V48 同時建立 actor-isolated `flexible_day_task_plan`。只有目標日期、沒有固定鐘點的事項不建立
00:00–24:00 `ScheduleItem`，因此不會虛假占滿行事曆；Java 透過 `FreeSlotService` 從已確認且會占用
本人時間的行程中選第一個 08:00–23:00 可用空檔，再以既有 `TaskService`／可靠提醒佇列建立任務。
提醒至少在系統收到設定後 5 分鐘；使用者指定鐘點時保留明確鐘點，未指定才由 Java 提議空檔。

繳費通知的 `due_date` 與實際 `remind_at` 分開保存，`reminder_lead_days` 由 Java 用台北曆日計算。
Task 完成或取消事件會同步關閉 plan 與 notice；既有 reminder trigger／escalation 在 Task 終止狀態下
也會 fail closed，因此提前完成後不再送出後續提醒。原始圖片仍走私有媒體保存，辨識結果另綁定
「繳費通知」受控標籤，使用者可日後由 App／唯讀媒體查詢取得原圖。

## 29. 最近日常繳費紀錄查詢（2026-07-19）

`ASK_PAYMENT_HISTORY` 是不要求日期範圍的唯讀查詢，預設列出最近 10 筆、最多 50 筆已保存且可稽核的
`PriceRecord`。Java 依品名與商家確定性分類水電瓦斯、學雜費、停車、住房、電信、保險、稅費、醫療、
交通、轉帳與其他明確費用，回覆日期、項目、金額、商家／分類及加總；不讓 LLM 推測款項，也不把
`payment_notice` 未付款草稿算成繳費完成。

新寫入的消費會同步建立 `activity:payment` 與 `payment:<kind>` 標籤。為兼容標籤功能推出前的既有資料，
查詢時仍會用同一套 deterministic catalog 分類舊紀錄；像「咖啡」雖含同音字面片段，不會因為「啡」
被誤當成「費」。資料量成長後可再把分類回填成持久化索引，本階段不需 schema 或 API contract 變更。

## 30. 假日跳過週期與一致時間語意（2026-07-19）

V49 將條件式週期的國定假日政策擴充為 `SKIP`。只有官方來源確認該基準日為國定假日，該 occurrence
才記為 `SKIPPED` 且不提出行程；官方狀態未知仍停在 `WAITING_OFFICIAL_CONFIRMATION`。補課不由系統
推算或自動建立，老師另行提供後才按一般單次行程處理。原句缺課程時長或「七點」未說上午／晚上時，
只澄清；下一句同時補齊後才建立待啟用規則草稿，仍需明確啟用才開始解析 occurrence。

`ChineseTimePeriod` 是 deterministic 中文時段的單一規則來源：凌晨／上午／早上為 AM，下午／晚上／
黃昏／傍晚為 PM；中午 11:30、12:30、1:00 分別解析為 11:30、12:30、13:00。空檔、家庭接送、
行程更正、生活時窗、繳費提醒、限期草稿與條件週期共用此規則，LLM 不負責鐘點換算。

V50 的 actor-isolated `time_display_preference` 讓使用者選擇所有人類可讀時間採 12 或 24 小時制，
未設定預設 24 小時制。聊天與 LINE 回覆在 `IntentService` 出口統一格式化；提醒、草稿到期與天氣
警示等主動通知則由 outbox 按 `target_user_id` 逐 actor 領取，在建立 delivery envelope 前套用收件人
偏好。REST response 中的結構化時間與資料庫仍維持 ISO 格式，因此不改既有 API contract，也不會
把同 workspace 其他成員的偏好套錯。

## 31. 每月第 N 個星期週期（2026-07-19）

`ScheduleItem.Recurrence.MONTHLY_NTH_WEEKDAY` 以第一場日期同時保存週次與星期語意，不增加資料庫欄位：
例如 2026/08/03 是第一個星期一，rollover 必須由 `ScheduleRecurrenceCalculator` 算出 2026/09/07，
不能用固定 28／30 日，也不能降級成 `WEEKLY`。第五個星期幾在部分月份不存在時，Java 會略過沒有該
日期的月份，直到下一個合法 occurrence；日程預覽也使用同一 calculator 判斷。

`MonthlyOrdinalRecurrenceConversationService` 在呼叫 LLM 前攔截明確月週次語句。缺少時長、開始時間、
AM／PM 或標題時只澄清且不落盤；跨句補齊後才透過 `ScheduleService` 建立並走既有可行性關卡。
週期值仍是既有 response 欄位的新增 enum 值，schema 與 API 結構未改，且不需要 Flyway migration。

## 32. 單一條件場地決策（2026-07-19）

V51 新增 actor-isolated `conditional_venue_draft`，保存活動時間、原定／備用場地、決策時間、提醒 task、
最終選定場地與唯一 schedule ID。初始語句即使同時提到「健身房」與「家」，也不建立兩個互斥行程；
缺時長或決策鐘點未說 AM／PM 時先澄清。補齊後只建立決策提醒與草稿，明確選定場地後才呼叫一次
`ScheduleService`，並完成決策 task。

場地文字不等於可導航地點。若選項能對上既有 `Place` 才綁 `placeId`；泛稱「健身房」在缺少座標與
Places gateway 時仍保存為草稿的 `selectedPlaceName`，但行程不綁假座標，回覆也明說尚未綁定精確
導航地點。此設計保證單一行程、保留使用者原意，且不以 LLM 或外部服務缺省值捏造位置。

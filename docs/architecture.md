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
| AI 層 | Spring AI + LLM API（tool calling） | LLM 只負責理解與表達，排程判斷交給自寫演算法 |
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

1. **意圖理解（LLM）**：把自然語言（打字／Siri 語音／LINE 轉傳／收據照片）轉成結構化 JSON。用 Spring AI 的 tool calling 加 structured output。
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
3. **意圖系統演進**：free-form intent command（108 種）之上疊了 typed capability registry（目前 shadow 驗證模式），逐步把 LLM 輸出遷移到有 schema、有風險分級（QUERY／MUTATION／DESTRUCTIVE）的能力目錄。LLM provider 定案 Spring AI + Anthropic；prompt injection 防護政策見 `docs/security-deployment.md`。
4. **通知通道現況**：APNs 尚未存在；實際通道為 server log、Windows Toast 與 LINE，經 notification outbox pattern 送出。
5. **事件匯流排現況**：仍為 Spring Events（§9 原規劃 Phase 2–3 換 Redis Streams 未執行，順延 Phase 4 評估）；Redis 用於外部 API 快取與延遲提醒佇列。
6. **開發自動化**：`internal/ai-dispatcher` 獨立應用（見 §6 末段）控制 Codex 開發 agent，與產品 runtime 完全隔離。

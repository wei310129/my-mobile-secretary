# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 溝通與語言

- 一律以**繁體中文**回覆;講解由淺入深、白話。
- 使用者是三年經驗的 Java 後端工程師。不要提供 Spring Boot 3.5 / Java 21 之前的舊版寫法。

## 專案是什麼

「分身秘書 App」——情境感知的個人排程與提醒系統(不是待辦清單,而是能主動在對的時間、對的地點提醒使用者)。核心價值排序:**提醒的可靠度 > 提醒的聰明度**。

**現況(2026-07-17 盤點)**:後端已具規模,不是骨架。Phase 0-2 已完成(提醒核心閉環、規劃引擎、外部整合),Phase 3(AI 對話)進行中且已深入——LINE Bot 是目前主要互動介面,意圖解析支援 108 種意圖。iOS 端(SwiftUI)因尚無 Mac 環境仍未動工,手機事件以 API 模擬,通知走 server log、Windows Toast 與 LINE。

**動手前必讀**,設計決策的最終依據:
- `docs/architecture.md` — 產品定位、技術選型、系統架構、AI 五層設計。
- `docs/development-plan.md` — 實作導向計畫:各 Phase 交付物與**進度狀態**、決策紀錄、程式碼/測試/註解規範。與本檔衝突時,以開發計畫為準。
- `docs/test-strategy.md` — 變更相關性導向的精準測試策略(日常改動不全跑測試)。
- `docs/security-deployment.md` — RLS 角色分離、SQL injection 與 prompt injection 防護政策。
- `internal/ai-dispatcher/`(README/ARCHITECTURE/DESIGN)— 動到該目錄時必讀。

## 常用指令

本專案是 Maven wrapper 專案,Windows 用 `mvnw.cmd`(Git Bash 下用 `./mvnw`)。

```powershell
# 日常開發環境(主應用 + ai-dispatcher + 兩套 Docker Compose 一起管理)
.\scripts\dev-start.ps1                       # 啟動全部(加 -SkipDispatcher 只跑主應用)
.\scripts\dev-status.ps1                      # 檢查狀態
.\scripts\dev-restart.ps1                     # 重啟 Java 應用
.\scripts\dev-stop.ps1                        # 停 Java 應用(-Docker 連 Compose 一起停)

# Maven(主應用)
./mvnw.cmd test                               # 跑全部測試(重節點才跑,平時見 test-strategy.md)
./mvnw.cmd test -Dtest=ClassName              # 跑單一測試類別
./mvnw.cmd test -Dtest=ClassName#methodName   # 跑單一測試方法
./mvnw.cmd -DskipTests test-compile           # 只編譯主程式與測試來源
./mvnw.cmd spring-boot:run                    # 本機啟動後端
./mvnw.cmd clean package                      # 打包(產出可執行 jar)

# ai-dispatcher(獨立 Maven 專案,不在根 build 內)
./mvnw.cmd -f internal/ai-dispatcher/pom.xml test
./mvnw.cmd -f internal/ai-dispatcher/pom.xml spring-boot:run
```

日常測試遵循 `docs/test-strategy.md`:依變更的依賴圖挑最小測試集,只有大節點(提交大功能、跨 3+ 模組、動共用機制、準備 PR)才跑完整 `mvn test`。

## 架構核心原則

**薄手機、厚後端。** 所有判斷、規劃、記憶都在後端;手機端只做感測、顯示、通知。這確保未來 Android 版只需重寫薄殼,LLM 與外部 API 整合天生屬伺服器端。

**後端採模組化單體(modular monolith)**——單一 Spring Boot 專案,靠 package 邊界維持分工,模組間只透過介面與事件溝通。基礎套件:`com.aproject.aidriven.mymobilesecretary`。現有模組:

| 模組 | 職責 |
|---|---|
| `api` | REST 入口(14 個 controller:task/place/location/reminder/schedule/item/weather/intent/line/internal) |
| `account` | 帳號、workspace 多租戶隔離(PostgreSQL RLS)、security audit、idempotency |
| `family` | 家人通知理解、隱私家人身分檔案(不外洩至 LLM) |
| `geo` | 位置事件、geofence 命中、距離/半徑判斷(全系統唯一出處) |
| `intent` | LLM 意圖理解(108 種意圖)、typed capability registry(shadow 驗證)、對話上下文、intent issue 回饋閉環 |
| `integration` | 外部轉接層:LINE、通知(log/Windows Toast/outbox)、Google Places、TDX 交通、氣象署、development feed |
| `knowledge` | 物品、庫存、價格歷史 |
| `planner` | 確定性規劃:可行性把關、空檔/順路建議、交通時間估算、天氣規則 |
| `reminder` | 任務狀態機、提醒排程、debounce、升級催促 |
| `schedule` | 行程(source of truth)、週期行程、pending 池與空閒詢問、行程結果追蹤 |
| `travel` | 旅行規劃引導、行李清單與長期偏好、行程表圖片草稿 |
| `shared` | error/time/validation/security/observability |

**多租戶與資料歸屬**:workspace/RLS 基礎已上線(V18/V25/V30)——新資料表**一律掛 `workspace_id`** 並遵循既有 RLS pattern(參照 `WorkspaceOwnedEntity` 與 `docs/security-deployment.md`)。家庭「共享/共編」**功能**仍排遠期(開發計畫 Phase 5),但舊的「Phase 5 前不預加 user_id」規則已作廢。位置事件永遠不共享。

依賴方向必須守住:
- `api` → application service → `domain`;`api` 不直接呼叫 repository。
- `domain` 不依賴 Spring Web,也不直接呼叫 repository(只依賴 repository 介面)。
- `integration` 不得把外部 API response 直接洩漏進 `domain`。

**AI 分層鐵律:LLM 不做排程計算。** 排程/地理/時間窗/可靠度判斷一律由確定性 Java 規則引擎(`planner`)處理;LLM 只負責「意圖理解」(自然語言 → 結構化 command)與「表達」(計算結果 → 貼心推播文字),且 response 一律驗證 schema,LLM 失敗不能讓提醒核心不可用。LLM 走 Spring AI 1.1.5 + Anthropic(意圖解析與收據多模態);prompt injection 防護政策見 `docs/security-deployment.md`。意圖系統正逐步從 free-form command 遷移到 typed capability registry(目前 shadow 驗證模式)。

**事件驅動的動態行事曆:** 任一事件(GPS 進出、定時、行事曆變動、天氣、使用者回報)都觸發規劃引擎重新評估。事件匯流排現況:仍為 Spring Events(+ Redis 快取/延遲佇列 + notification outbox);Redis Streams 與 Kafka 是 Phase 4 的未來項目,尚未導入。

## internal/ai-dispatcher(開發自動化,非產品 runtime)

`internal/ai-dispatcher` 是同 repo 內**完全隔離的獨立 Spring Boot 應用**(自己的 pom、PostgreSQL、Flyway、Compose),負責控制 Codex 開發 agent 何時啟動與 run 生命週期。鐵律:不得 import 主應用 classes、主應用不得呼叫或等待它、跨應用只透過版本化 HTTP contract(development feed)、刪除該目錄不影響主應用編譯執行。

**Codex 並行開發注意**:session 進行中 HEAD 與工作區可能隨時被 Codex 的 commit 移動——任何 git 操作(commit、rebase、診斷 diff)前先重查 `git status` 與 `git log`,不要沿用開場快照。

## 程式碼慣例(見開發計畫第 4 節)

- Controller 只處理 HTTP;Service 做 use case orchestration;domain method 負責自身狀態轉換與規則;repository 只做資料存取。
- 時間計算一律注入 `Clock`,不直接用 `now()`,以便測試。
- 位置距離/半徑判斷集中在 `geo` 模組,不各處重寫。
- HTTP DTO、Entity、Domain Model 職責分清,不把 DTO 當核心 domain。
- 方法名要說明意圖,避免 `process`、`handleData` 這類模糊名稱。
- Spotless 強制 import 檢查:提交前跑 `.\scripts\spotless-apply.ps1` 或 `./mvnw.cmd spotless:apply`。

**註解比一般後端更重視**(排程/地理/提醒閉環有大量隱性規則)。必寫註解:public controller 方法、application service public 方法、domain 改變狀態的方法、`planner`/`geo`/`reminder` 核心規則方法、外部 API client、以及複雜方法內部的關鍵決策點(debounce、半徑判定、狀態轉換、提醒升級)。註解描述「原因與約束」,不逐行翻譯 Java 語法。

## 測試要求(見開發計畫第 5 節與 docs/test-strategy.md)

**每個 API 與每個關鍵方法都要有測試。** 每個 API 至少涵蓋:成功、request validation 失敗、重要業務錯誤(任務/地點不存在、非法狀態轉換)。關鍵方法測試優先確保:位置命中/未命中、同地點重複進入不連續提醒、狀態不能非法跳轉、到點提醒被撈出、未確認提醒會升級、外部 API 失敗不拖垮核心提醒。

分層:JUnit 5 + AssertJ + Mockito(純邏輯)、`@WebMvcTest`/MockMvc(controller)、`@DataJpaTest` + Testcontainers(PostgreSQL/PostGIS mapping 與 SQL)、Testcontainers(端到端)、MockWebServer/WireMock(外部 client,不打真實 API,要測 timeout/非 2xx/空 response/格式錯誤)。

日常開發**不**每次全跑:依 `docs/test-strategy.md` 的變更相關性策略挑測試,並誠實回報「跑過什麼、沒跑什麼」,不得把精準測試通過寫成全套通過。

## 何時必須先問使用者(見開發計畫第 17 節)

不要自行決定以下事項,先通知使用者:版本跨 minor/major 升級(例 Spring Boot 3.5→4.x)、新增有成本的外部服務(Google Places、LLM API、Apple Developer)、使用真實個資/位置、產品行為(提醒頻率、升級次數、狀態名稱)、導入大型技術(Kafka、Spring Cloud、K8s)、改變 MVP 範圍、刪資料或 destructive migration、測試策略在真實服務與 mock 之間切換。

可自行決定:小型 class/package 命名、內部方法拆分、測試檔名、不改變行為的重構、不跨版本線的 patch 更新。

## 版本策略

Java 固定 `21`。維持 Spring Boot **3.5.x**(現為 3.5.16),**不**升到 Spring Boot 4.x(周邊教材/整合慣性較穩,Spring AI 1.1.x 明確支援 3.4/3.5)。Spring AI 固定 `1.1.5`。patch 版可小幅升。

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 溝通與語言

- 一律以**繁體中文**回覆;講解由淺入深、白話。
- 使用者是三年經驗的 Java 後端工程師。不要提供 Spring Boot 3.5 / Java 21 之前的舊版寫法。

## 專案是什麼

「分身秘書 App」——情境感知的個人排程與提醒系統(不是待辦清單,而是能主動在對的時間、對的地點提醒使用者)。核心價值排序:**提醒的可靠度 > 提醒的聰明度**。

目前 repo 只有後端 Spring Boot starter 骨架。iOS 端(SwiftUI)因尚無 Mac 環境,Phase 1 一律以 API + server log 模擬手機事件與推播,不做真正 iOS App。

**動手前必讀**,兩份文件是所有設計決策的最終依據:
- `docs/architecture.md` — 產品定位、技術選型、系統架構、AI 五層設計。
- `docs/development-plan.md` — 實作導向計畫:各 Phase 交付物、API 清單、測試要求、決策停靠點、程式碼/測試/註解規範。與本檔衝突時,以開發計畫為準。

## 常用指令

本專案是 Maven wrapper 專案,Windows 用 `mvnw.cmd`(Git Bash 下用 `./mvnw`)。

```powershell
./mvnw.cmd test                              # 跑全部測試
./mvnw.cmd test -Dtest=ClassName             # 跑單一測試類別
./mvnw.cmd test -Dtest=ClassName#methodName  # 跑單一測試方法
./mvnw.cmd spring-boot:run                    # 本機啟動後端
./mvnw.cmd clean package                       # 打包(產出可執行 jar)
./mvnw.cmd spring-boot:build-image             # 建 OCI image
```

## 架構核心原則

**薄手機、厚後端。** 所有判斷、規劃、記憶都在後端;手機端只做感測、顯示、通知。這確保未來 Android 版只需重寫薄殼,LLM 與外部 API 整合天生屬伺服器端。

**後端採模組化單體(modular monolith)**——單一 Spring Boot 專案,靠 package 邊界維持分工,模組間只透過介面與事件溝通,未來可直接拆微服務。基礎套件:`com.aproject.aidriven.mymobilesecretary`。規劃中的模組:`api`、`geo`、`reminder`、`planner`、`schedule`(Phase 2 起,行程與可行性把關)、`knowledge`、`integration`、`shared`(詳細子結構見開發計畫第 6 節)。

**多使用者(家庭共享)排 Phase 5**:在那之前系統維持單人設計,新資料表**不要**預加 user_id(Phase 5 一次遷移);位置事件永遠不共享。

依賴方向必須守住:
- `api` → application service → `domain`;`api` 不直接呼叫 repository。
- `domain` 不依賴 Spring Web,也不直接呼叫 repository(只依賴 repository 介面)。
- `integration` 不得把外部 API response 直接洩漏進 `domain`。

**AI 分層鐵律:LLM 不做排程計算。** 排程/地理/時間窗/可靠度判斷一律由確定性 Java 規則引擎(`planner`)處理;LLM 只負責「意圖理解」(自然語言 → 結構化 command)與「表達」(計算結果 → 貼心推播文字),且 response 一律驗證 schema,LLM 失敗不能讓提醒核心不可用。

**事件驅動的動態行事曆:** 任一事件(GPS 進出、定時、行事曆變動、天氣、使用者回報)都觸發規劃引擎重新評估。事件匯流排演進:Phase 1 用 Spring Events → Phase 2-3 換 Redis Streams → Phase 4 換 Kafka。

## 程式碼慣例(見開發計畫第 4 節)

- Controller 只處理 HTTP;Service 做 use case orchestration;domain method 負責自身狀態轉換與規則;repository 只做資料存取。
- 時間計算一律注入 `Clock`,不直接用 `now()`,以便測試。
- 位置距離/半徑判斷集中在 `geo` 模組,不各處重寫。
- HTTP DTO、Entity、Domain Model 職責分清,不把 DTO 當核心 domain。
- 方法名要說明意圖,避免 `process`、`handleData` 這類模糊名稱。

**註解比一般後端更重視**(排程/地理/提醒閉環有大量隱性規則)。必寫註解:public controller 方法、application service public 方法、domain 改變狀態的方法、`planner`/`geo`/`reminder` 核心規則方法、外部 API client、以及複雜方法內部的關鍵決策點(debounce、半徑判定、狀態轉換、提醒升級)。註解描述「原因與約束」,不逐行翻譯 Java 語法。

## 測試要求(見開發計畫第 5 節)

**每個 API 與每個關鍵方法都要有測試。** 每個 API 至少涵蓋:成功、request validation 失敗、重要業務錯誤(任務/地點不存在、非法狀態轉換)。關鍵方法測試優先確保:位置命中/未命中、同地點重複進入不連續提醒、狀態不能非法跳轉、到點提醒被撈出、未確認提醒會升級、外部 API 失敗不拖垮核心提醒。

分層:JUnit 5 + AssertJ + Mockito(純邏輯)、`@WebMvcTest`/MockMvc(controller)、`@DataJpaTest` + Testcontainers(PostgreSQL/PostGIS mapping 與 SQL)、Testcontainers(端到端)、MockWebServer/WireMock(外部 client,不打真實 API,要測 timeout/非 2xx/空 response/格式錯誤)。

## 何時必須先問使用者(見開發計畫第 16 節)

不要自行決定以下事項,先通知使用者:版本跨 minor/major 升級(例 Spring Boot 3.5→4.x)、新增有成本的外部服務(Google Places、LLM API、Apple Developer)、使用真實個資/位置、產品行為(提醒頻率、升級次數、狀態名稱)、導入大型技術(Kafka、Spring Cloud、K8s)、改變 MVP 範圍、刪資料或 destructive migration、測試策略在真實服務與 mock 之間切換。

可自行決定:小型 class/package 命名、內部方法拆分、測試檔名、不改變行為的重構、不跨版本線的 patch 更新。

## 版本策略

Java 固定 `21`。維持 Spring Boot **3.5.x**(現為 3.5.16),Phase 1 **不**升到 Spring Boot 4.x(周邊教材/整合慣性較穩,Spring AI 1.1.x 明確支援 3.4/3.5)。patch 版可小幅升。

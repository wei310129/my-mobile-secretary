# 變更相關性導向測試策略

## 為什麼縮小日常測試範圍

專案在 2026-08-04 有 390 份測試 Java 來源、384 個可執行 test class（2026-07-17 為 147 份；文件初版時為 57 份），其中多數 API／repository 測試會啟動
Spring、PostgreSQL/PostGIS 與 Redis Testcontainers。
這些完整整合測試適合驗收關鍵節點，但每個小修改都全跑，等待時間與輸出量會快速放大。

近期可觀察結果（2026-07-16）：

- 第一波生活對話功能：主程式與全部測試來源 `test-compile` 成功。
- 100 條能力目錄契約：1/1 通過。
- 第二波能力目錄與週期任務服務：4/4 通過。
- 第三波能力目錄與庫存 domain/service：8/8 通過。
- 第四波能力目錄、勿擾時間計算、提醒觸發與升級催促：16/16 通過；
  Maven 同時重新編譯 204 份主程式來源與 59 份測試來源成功。
- 第五波能力目錄、geofence domain 邊界與唯一規則修改／移除：15/15 通過。
- 第六波能力目錄與行程洞察：5/5 通過；Maven 重新編譯 205 份主程式與 61 份測試來源成功。
- 第七波能力目錄與待辦優先／進度洞察：4/4 通過；Maven 重新編譯 206 份主程式與 62 份測試來源成功。
- 第八波能力目錄與待辦期限／負荷洞察：6/6 通過；精準測試確認既有編譯輸出為最新，未擴跑整合測試。
- 第九波能力目錄與行程負荷／地點洞察：7/7 通過；僅跑能力契約與行程洞察單元測試。
- 第十波能力目錄與價格紀錄洞察：4/4 通過；Maven 重新編譯 207 份主程式與 63 份測試來源成功。
- 第十一波品項洞察與 LINE 實際問題修正：能力目錄、品項洞察、可行性與行程洞察 17/17 通過；
  Maven 重新編譯 208 份主程式與 64 份測試來源成功。修正衝突細節／建議、行程提醒查詢；
  同名任務編號選擇再跑能力契約 1/1 通過。
- LINE 跨使用者隱私修正：`LineOwnerGuardTest` 2/2 通過；owner 未設定時由全放行改為全阻擋。
- LINE 能力介紹誤回內部分類理由：`IntentServiceCapabilityHelpTest` 1/1 通過，改為確定性使用者說明。
- LINE 地點選錯分店：`PlaceAliasServiceTest` 1/1 通過；更具體的分店查詢不再命中舊短名稱。
- LINE 待辦建議混淆期限與地點：`TaskAdviceClassificationTest` 1/1 通過；逾期、無期限、缺地點分開回覆。
- LINE 長篇上班日常被誤判為回饋：`RecurringRoutineClarificationTest` 1/1 通過，改問實際缺少的固定時段決策。
- 對話回覆格式統一：格式器、收據、LINE client、一般提醒、天氣通知、待安排追問、行程結果追問與既有特殊回覆共 44/44 通過；
  驗證多項目條列、區塊空行、對應 emoji、LINE JSON 實際文字與重複格式化不變形。
- LINE 每日行程總覽漏掉固定上班行程：每日總覽、日期查詢攔截、巢狀行程與格式器局部測試 22/22 通過；
  `RecurringScheduleFlowTest` 4/4 通過，並以真實 PostgreSQL 驗證 V16 migration 與 `WEEKDAYS` rollover。
- LINE 運動安排忽略九點洗澡提醒：提醒時間查詢、待辦與行程衝突說明、可行性規則單元測試 16/16 通過；
  `RecurringScheduleFlowTest` 4/4 通過，確認新增 intent／planner 依賴可由完整 Spring Context 正常注入。
- 第二波 `LifestyleIntentApiTest`：測試環境找不到 Docker，Spring context 在案例執行前停止；
  因此 V14/V15 migration 與 API 整合仍列為關鍵節點待驗證，不算程式測試失敗，也不算通過。
- 本階段尚未重跑完整 `mvn test`，不得把精準測試通過誤寫成全套通過。

## 每次修改的預設選擇

1. 純 domain／service 規則：只跑對應單元測試，並讓 Maven 編譯所有受影響來源。
2. Intent type、schema 或能力目錄：加跑 `ConversationCapabilityCatalogTest`。
3. JPA entity 或 Flyway migration：在該批次收尾時跑最小相關 integration test，確認 migration 與 mapping。
4. Redis reminder 流程：只有動到 queue member、claim、排程同步或 worker 時，才跑 reminder flow 測試。

## 開發協調工具

- Coordinator kernel、Maven/lifecycle adapter 與 handoff 一律先跑各自的 fake/disposable PowerShell gate；不得以
  shared Compose、dev volume、Flyway history 或真實 LINE endpoint 作為 failure injection fixture。
- Managed runtime lifecycle修改必須用controlled process fixtures覆蓋caller-return persistence、banner-only、
  receipt/state publication後消失、`CALLER_ACCESS_DENIED`／`UNKNOWN`／`DOWN`、exact orphan reconcile、
  interrupted stop replay與敏感資訊遮蔽。live fixture只可啟動無網路的短效process，跨caller確認後精確停止；
  不得用Calendar worktree、真實LINE或產品mutation作證據。
- Managed process identity precision修改必須覆蓋native-only publication後的WMI＋native consensus、100ns／
  亞微秒來源差異、至少1微秒的真正creation-time差異與PID reuse，並重驗component、worktree、executable、
  command、generation、stale／wrong／replayed receipt都維持fail closed；canonical precision必須明載於receipt。
- Testcontainers integration 維持 serial；未完成 per-test infra 隔離前，不啟用 JUnit class/method parallel。
- Dispatcher pause/drain、migration 與 protected management API 變更須跑最小 Dispatcher integration test；主應用與
  Dispatcher Maven target 不可在同一 worktree 同時寫入。
5. Controller／DTO：跑對應 API test；未改 controller 的 service 小修不重跑所有 API。
6. 外部 API client：只跑該 client 測試，不打真實服務。
7. 意圖確定性攔截（合併確認/拒絕、模糊時間守門 `VagueTimeGuard`）：跑對應單元測試
   （`DailyScheduleQueryTest`、`VagueTimeGuardTest`），改到攔截順序時加跑 `IntentApiTest`。
   注意攔截詞可能出現在任務/行程標題裡的誤攔情境要有測試。

## 必須跑完整 `mvn test` 的節點

- 一個較大功能階段準備提交或發布。
- 新增／修改多個 migration，或跨越 intent、task、schedule、reminder 三個以上模組。
- 修改共用狀態機、全域例外處理、Spring wiring 或 Testcontainers 設定。
- 精準測試出現無法由局部依賴解釋的失敗。
- 合併分支、準備 PR 或部署前。

## 目前常用命令

```powershell
# 日常第一輪：排除 integration／live，目標 30–60 秒內取得可信結果
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Fast

# 依 working tree 路徑選擇；未知、高風險或跨三模組變更會 fail-closed 升級 Full
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Relevant

# 所有 deterministic automated tests；只排除明確標記的 live evaluation
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Full

# 編譯主程式與全部測試來源，不執行測試
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -DskipTests test-compile

# 生活語句能力目錄 + 週期任務規則（不需 Docker）
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,TaskLifestyleServiceTest" test

# 關鍵節點完整回歸
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
```

測試範圍以「變更的依賴圖與失敗後果」決定，不用固定成功率門檻猜測品質；每次交付都要明確列出實際跑過與尚未跑的範圍。

## 分層、選測與 CI gate（2026-08-04）

### 固定分層

- `fast`：不啟動完整 Spring／Testcontainers 的純 Java domain、application policy、formatter 與契約測試。
- `integration`：完整 Spring wiring、MockMvc、PostgreSQL/PostGIS、Redis、Flyway、RLS、outbox 或 concurrency 測試。
- `live`：需要真實 model／外部服務或人工授權的 opt-in evaluation；一般 automated regression 永遠排除。
- `migration`、`rls`、`conversation`、`latency` 是風險 traits，不取代 primary lane。
- `IntegrationTestBase` 統一帶 `integration` tag；獨立 Testcontainers migration test 同時帶
  `integration`／`migration`。新增高成本測試不得依檔名猜分類。

`scripts/test-inventory.ps1` 以 test source、Spring／Testcontainers 種子與 class inheritance 建立 inventory。
未分類測試仍屬 automated；report aggregator 要求 381 個 automated test class 在 Fast＋三個 Integration
shard 中恰好各出現一次，漏測、重複或額外 suite 都失敗。

### Relevant fail-closed 規則

- 一般單模組 Java 變更先跑 Fast，再追加該模組的 integration classes。
- intent／conversation 與 booking／execution／payment 使用已知相鄰模組集合。
- `pom.xml`、Flyway、共用 API／shared、整合測試基底、test tooling、未知路徑或三個以上模組直接 Full。
- `Relevant` 只讀 `git status --porcelain`，不依賴未受控的 Git diff；呼叫端也可用 `-ChangedPaths` 明示範圍。
- 精準測試不取代 PR、合併及部署前完整 gate；執行結果必須回報實際 lane 與升級理由。

### GitHub Actions

- `.github/workflows/test-gates.yml` 使用 Java 21 Ubuntu ephemeral runners 與 Maven dependency cache。
- PR 先執行 required `Merge policy`：只接受已登錄 branch ownership，驗證 changed-path allowlist、敏感
  刪除／改名、Flyway immutability／版本唯一性、handoff 狀態／SHA ancestry 與一次性 trigger 消耗一致性。
  未登錄 branch、跨 lane 寫入或 durable state 矛盾一律 fail closed。
- Agent 實際合併只能經 `scripts/merge-pr.ps1`，以使用者當輪授權的 PR／head SHA 重新查核 GitHub；
  head 改變、draft、非 CLEAN、required check 不綠、branch protection 不 strict 或管理員可 bypass 都拒絕。
- Commit 前可用 `scripts/merge-policy.ps1 -UseWorkingTreeChanges` 依 `git status --porcelain` 驗證本機範圍；
  CI 不信任本機摘要，仍從 GitHub Pull Files API 重新取得完整 changed-file 清單。
- `pom.xml`、test／CI tooling、migration、application config 或 integration base 等高風險路徑會在 PR
  合併前加跑 one-JVM `PR risk serial regression`；`Merge policy` 只有在必要 serial 成功後才成功。
- PR required checks 應設定為 `Merge policy`、`Fast tests`、三個 `Integration shard` 與
  `Automated regression complete`；integration job 之間並行，單一 job 內保持 serial。
- `main` push 額外執行 `Main serial regression`，偵測 context cache、順序或跨 suite 污染；部署只能使用
  同一 SHA 已通過 PR 聚合與 main serial regression 的版本。
- CI 不快取 `target`、資料庫或 container state，不使用 Testcontainers experimental reuse；失敗測試不得
  靜默 retry、永久 quarantine 或以新增 skip 維持綠燈。
- 第一階段不設 coverage 百分比硬閘；保留 Surefire XML 14 天，先以每 job 時間、slow suites、skip 與
  shard imbalance 建立趨勢。完成穩定觀察後再決定 coverage 趨勢產物，不把低價值百分比當品質替代品。

### 效能目標與逐批重整

- 一般純 domain／service 變更：Fast cold P90 ≤ 60 秒、warm P90 ≤ 30 秒。
- Relevant 必須先在 60 秒內交付 Fast 結果；需要容器的 focused integration 可繼續執行。
- PR 三個 integration shards：初始目標 median ≤ 6 分鐘、P90 ≤ 8 分鐘；以 GitHub 實測校正 shard，
  不以刪案例達標。
- 後續一次只重整一個 subsystem：抽出純 Java policy、縮窄 controller protocol test、保留每個 domain
  至少一條真實 entry path；repository、Flyway、RLS、transaction、async／outbox 仍使用真實基礎設施。
- fixture reset 僅在證明 transaction rollback 等價後才縮小；HTTP、async、commit visibility、RLS
  transaction-local 與 migration 案例維持必要的真實 reset。

### Context 壓縮提醒點

- **CI 基礎完成**：inventory、Fast／Relevant／Full、三 shards、aggregator 與 main serial gate 全部驗證後，
  主動提醒適合壓縮；續作摘要保留目前階段、已拍板決策、不變量、修改檔案、雙機基準、測試結果、
  未完成工作、下一步與風險。
- **每個 subsystem 重整出口**：該批 Fast／Relevant／PR Full／main serial 全綠且行為等價已記錄後才提醒；
  migration 中途、測試失敗未定位或仍依賴大量未摘要上下文時不得提醒。
- **20 個 PR 穩定觀察完成**：保留最終 SHA、P50／P90、漏選、flaky、shard imbalance、例外與後續治理規則。

## 現況快照（2026-08-04）

- 主應用：390 份測試 Java 來源、384 個可執行 test class；inventory 為 Fast 257、Integration 124、
  Live 3，automated 合計 381。桌電 2026-08-02 既有 Surefire 報告為 1,618 tests、0 failure、0 error、
  16 skipped，test class 累計 311.4 秒；歷史完整 Maven wall time 約 319.6–836.8 秒。
- `internal/ai-dispatcher`（獨立 Maven 專案）：72 份 Java 來源、13 份測試類別；測試指令
  `.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test`，與主應用測試互不影響。
- 上方逐波觀察紀錄保留為歷史 log；新的觀察請依日期續記，不回改舊紀錄。

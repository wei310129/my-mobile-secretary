# Repository guidance

- 所有工作摘要、風險與測試結果使用繁體中文回報。
- Runtime 基準為 Java 21、Spring Boot 3.5.x、Spring AI 1.1.x。
- LLM 只負責自然語言理解與表達；目前使用 Structured Output，不把 LLM 當業務執行器。
- 排程、時間範圍、地理判斷、狀態機與 destructive action 必須由 Java 驗證並透過 application/domain service 執行。
- Controller 只處理協定與輸入輸出，不放商業邏輯；domain 不得依賴 Web/API package。
- 時間邏輯使用注入的 `Clock`，不可直接依賴系統現在時間造成不可測試行為。
- Schema 一律由 Flyway migration 管理；不得用 Hibernate 自動改 schema。
- 保持 `spring.jpa.open-in-view=false`。
- 新增 Intent Type 時必須同步補齊對應的領域 Handler、`conversation-capabilities.txt` 能力目錄與 regression test。
- 新增使用者可感知的 domain event 時，必須同步接入通用 LifeRecord／tag graph recorder；開發回饋不記為生活事件。
- 不得靜默更改使用者已拍板的提醒頻率、緩衝時間或產品行為；需要變更時先取得確認。
- 不得把 secrets、API key 或本機 `secrets.yaml` 提交進版控。

## 開發 context 與輸出控制

- 修改前先回報本次目標、預計檢查的資料夾、候選檔案與驗證方式。
- 預設只搜尋任務直接相關的 1–3 個資料夾；只有發現明確依賴時才擴大，並先說明依賴與擴大原因。
- 搜尋與讀取預設排除 `target/`、`scripts/.logs/`、其他日誌、快取、產生碼與無關文件；不得先做全專案掃描。
- 輸出量未知的指令必須使用工具原生限制、精準篩選或摘要器；不得把未受限的完整輸出直接送入對話。
- 為節省對話 token，整個開發 session（包含中途工具輸出、進度更新與最終回報）都不得把程式碼 diff 顯示到對話。除非使用者當輪明確要求查看 diff，禁止執行任何 `git diff` 系列指令（包含 `git diff --check`），避免工具介面即使沒有內容仍顯示 diff 區塊；也禁止其他會把 diff 內容直接送入 session 的指令。需要檢查變更時，改用 Spotless、編譯、測試、`git status --short` 或不含 diff 的精準摘要器；固定只提供修改摘要、精準涉及範圍、測試結果與風險。
- 規劃可由 Terra 在同一 session 持續執行到完成的開發文件時，文件必須明列「Context 壓縮提醒點」：每個提醒點要寫明觸發條件、已完成／已驗證的範圍、壓縮後必須保留的續作摘要（目前輪次、已拍板決策、不變量、修改檔案、驗證結果、未完成工作、下一步與風險）。適合的觸發點包括一個有明確出口的舵輪／階段已完成並記錄 gate 結果、複雜調查已收斂且即將轉入獨立實作或驗收階段；不得在未提交關鍵決策、migration／破壞性操作中途、測試失敗尚未定位，或仍需依賴大量未摘要上下文時列為提醒點。
- 到達文件列出的提醒點時，Terra 必須主動且簡短提醒使用者「現在是適合壓縮 context 的時機」，附上該文件指定的續作摘要；提醒只建議壓縮，不得自行壓縮、遺失已拍板決策或把壓縮當成跳過驗證的理由。若同一階段仍有直接相依的未完成工作，延後提醒至該階段出口。
- 一般功能開發不得順手調整啟停或工具腳本；只把已觀察到的具體需求登記至 `docs/tooling-backlog.md`，留待工具專用 session。

常用指令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-start.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-status.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
```

- 根專案 Maven lifecycle 一律優先透過 `scripts\mvn-safe.ps1` 執行：預設不 clean、跨程序互斥，
  避免多條開發線共用 `target`。只有確認為舊 class／產生碼污染或正式完整驗收時才明確加
  `-Clean`；`Cannot close compiler resources` 先確認無並行 Maven，再於工作區沙箱外以相同參數
  重試，不得把 clean 當第一步。
- GitHub Actions 的 Ubuntu ephemeral runner 是唯一例外：測試 workflow 必須透過
  `scripts/test.ps1 -Ci` 間接使用受限的 `scripts/mvn-ci.ps1`；該入口會驗證 `CI=true` 與
  `GITHUB_ACTIONS=true`，只允許 `test`／`test-compile`，不得用於本機、常駐 runner、clean、
  install、deploy、Spotless apply 或啟動服務。

- 開機後、LINE 無回應或需要確認完整服務時，先執行 `scripts\dev-start.ps1`，並以腳本內建的
  LINE 官方端到端測試為準；只有腳本回傳非零時，才從 LINE／ngrok／Spring Boot／Redis／
  PostgreSQL 逐層診斷，不得先重新手動探索或分別啟動各服務。

`docs/architecture.md` 說明產品與長期架構原則；`docs/decisions/current.md` 是目前已拍板決策入口；`docs/development-plan.md` 保留階段、驗收、進度與歷史追溯。實作若互相衝突，先確認現況與決策，不得直接覆寫產品語意。

## Java 服務版本交付

- 修改 Java、`pom.xml` 或主程式 resources 前先記錄 Spring Boot 是否正在運行。
- 本次範圍的修改完成且驗收通過後，預設自動建立 Git commit，讓流水號與 SHA 成為可精確追溯的交付版本；只有混入不明／非本次修改、測試未通過、提交邊界不清楚或需要使用者決策時，才先詢問是否提交或如何拆分。
- 自動 commit 成功後預設立即 push 至目前分支已設定的 upstream；只有缺少 upstream、遠端分歧／拒絕、憑證或網路失敗、可能包含敏感資料或需要改寫遠端歷史時才停止並詢問，不得自行 force push。
- 若修改前正在運行，完成後必須透過既有協調式 lifecycle 重啟主服務，並以 `/actuator/info` 驗證運行 SHA；不得只以 health=UP 宣稱最新版。若修改前停止，維持停止且明確回報未部署。
- 每次開發交付固定回報流水號、完整 Git SHA、主要改動、clean/dirty 與服務狀態。只有 clean checkout、clean build、流水號及 SHA 全部一致時才可回報 `RUNNING_CURRENT`；dirty、stale 或 metadata 缺失必須明示不可精確驗證。
- 流水號是 `git rev-list --count HEAD` 的易讀識別，可能在平行分支重複；完整 Git SHA 永遠是權威版本。

## 任務路由

- 開始任務時先讀 `docs/agent-context/index.md`，依任務類型只載入指定章節或 skill。
- 對話、Intent、LINE/chat 回覆、引用上下文或使用者可見行為使用 `.agents/skills/develop-and-evaluate-conversation-capability/`。
- `internal/ai-dispatcher/` 工作另遵循該目錄的 `AGENTS.md`；不要把其獨立 build、DB 與生命週期套用到主應用程式。

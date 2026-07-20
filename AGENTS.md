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
- 可在內部檢查受限範圍的 diff，但最終回報不得貼程式碼 diff；固定只提供修改摘要、精準涉及範圍、測試結果與風險。
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

- 開機後、LINE 無回應或需要確認完整服務時，先執行 `scripts\dev-start.ps1`，並以腳本內建的
  LINE 官方端到端測試為準；只有腳本回傳非零時，才從 LINE／ngrok／Spring Boot／Redis／
  PostgreSQL 逐層診斷，不得先重新手動探索或分別啟動各服務。

`docs/architecture.md` 說明產品與長期架構原則；`docs/development-plan.md` 記錄階段、驗收標準、進度與已拍板決策。實作若與兩者衝突，先確認現況與決策，不得直接覆寫產品語意。

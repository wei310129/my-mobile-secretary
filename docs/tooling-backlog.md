# Tooling backlog

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

### 精簡開發服務腳本的成功輸出

- 證據：`dev-start.ps1`、`dev-status.ps1`、`dev-stop.ps1` 的長日誌已寫入 `scripts/.logs/`，但成功路徑仍會輸出多段服務狀態資訊。
- 影響：一般啟停與健康檢查會占用不必要的開發 context，且重要的最終狀態不夠集中。
- 候選範圍：`scripts/dev-start.ps1`、`scripts/dev-status.ps1`、`scripts/dev-stop.ps1` 及其共用 helper。
- 建議驗收：成功時只保留單一摘要與必要端點；失敗時保留分層、受限且可定位 LINE／ngrok／Spring Boot／Redis／PostgreSQL 的診斷，既有 log retention 與 LINE 官方端到端驗證不變。
- 狀態：待工具專用 session 處理；本次不修改。

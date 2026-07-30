# Tooling backlog

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

- `scripts/dev-stop.ps1` 在 `.dev-state.json` 不存在時，`Read-DevState` 回傳空物件，
  但 StrictMode 下仍直接讀取 `dispatcherPid`，導致停止動作前即失敗。工具專用 session
  應補齊向後相容的空 state schema，並加入「無 state、僅有 coordinator-owned
  Spring Boot runtime」的安全停止測試。

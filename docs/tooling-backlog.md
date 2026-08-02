# Tooling backlog

## Spotless wrapper 錯誤回報

- `scripts/spotless-apply.ps1` 的 catch 訊息含無效格式字串；當協調狀態寫入失敗時，原始例外會被 `FormatError` 蓋掉，且 `$exitCode` 未設定。工具專用 session 應修正錯誤格式與保證非零 exit code，並加失敗路徑 gate。

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

目前沒有已登記的待處理項目。

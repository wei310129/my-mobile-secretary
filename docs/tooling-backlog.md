# Tooling backlog

## Spotless wrapper 錯誤回報

- `scripts/spotless-apply.ps1` 的 catch 訊息含無效格式字串；當協調狀態寫入失敗時，原始例外會被 `FormatError` 蓋掉，且 `$exitCode` 未設定。工具專用 session 應修正錯誤格式與保證非零 exit code，並加失敗路徑 gate。

只記錄已觀察到、值得由工具專用 session 獨立處理的問題。完成項直接移除，由 Git 歷史保留，避免清單持續膨脹；不登記推測性想法。

## 待處理

目前沒有已登記的待處理項目。

## 已觀察案例與決策索引

- 2026-08-05 `calendar-w11` 發生 stale Spring Maven launcher：`.dev-state.json` 仍記錄 PID 2228，
  coordination manifest 仍標 active，但 OS process／mutex 已不存在；舊 `dev-stop.ps1` 又因後續 ngrok
  verification failure 未逐 component 提交 state。處置決策是：Codex 只允許 repository-owned
  `mvn-safe.ps1`與不接受 PID 的 managed-stop wrapper；移除裸 `mvnw.cmd` allow。stop 必須同時驗證
  state／receipt、精確 worktree resource、process start time、command或 active launcher operation及
  competing owner，逐 component 原子清 state並保留 evidence。完成實作與 regression 後，此條只保留
  作案例索引，不代表尚待重做；另以 project execpolicy `forbidden` 阻擋 reset／clean／restore／stash
  類會改寫、刪除或隱藏 mixed-owner state 的 Git 操作。操作契約見 `scripts/README.md`。

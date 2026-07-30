# 雙機 Lane 角色對調計畫

> 狀態：`PREPARED_NOT_ACTIVE`
>
> 核准日期：2026-07-30
>
> 啟用 trigger：`TR-MACHINE-LANE-SWAP-MERGED`
>
> Machine-readable source：`handoffs/machine-lane-assignment.json`

## 1. 目的與已核准原因

使用者已回報筆電在長時間開發、Docker／Testcontainers 與完整回歸期間容易過熱當機。這是
`TR-HUMAN-AVAILABILITY-RISK` 的具體可靠度風險，不是單純偏好。為降低 gate 中途失敗、重跑與
人工切換成本，下一個雙方 safe boundary 起，將長時間、整合密集的 lane 移到桌電，較輕量且
容易切成小 gate 的 Commerce／ADD lane 移到筆電。

本文件只改「哪一台機器執行哪個角色」，不改 Calendar、Travel、Booking、ADD、Conversation
或 Schema 的產品語意、測試 gate、外部操作權限與 destructive safety gate。

## 2. 啟用前的真實狀態

| 項目 | Durable state |
| --- | --- |
| Booking B3-Core | `MERGED`；產品 SHA `ee1cfcbea8bf9b2c5ab36e8ac1922931ac24ddcf` |
| Booking B4-Fake | `MERGED`；產品 SHA `513eb117864cedbcaa63a99027cec22f9bd45bbf` |
| Calendar W10 | `PENDING`；尚無 `TR-CALENDAR-W10-MERGED` published SHA |
| Travel 3B-B | 等待 Calendar W10；W10 只解除依賴，不讓 3B-B 自動 PASS |
| B3-Durable schema token | `PENDING`；尚未 grant |
| Lane 對調 | `PREPARED_NOT_ACTIVE` |

在 `TR-MACHINE-LANE-SWAP-MERGED=READY` 以前，既有
`laptop-integration-lane-runbook.md`、`desktop-booking-add-lane-runbook.md`、
`laptop-trigger-state.json` 與 `desktop-trigger-state.json` 仍是唯一有效 ownership。

## 3. Current 與 Prepared assignment

| Role | 啟用前機器 | 啟用後機器 | 主要責任 |
| --- | --- | --- | --- |
| Upstream／Integration | Laptop | Desktop | Calendar W11+、Travel 3B-B 以後、Conversation／Intent／LINE、中央整合文件、共用 wiring、Flyway sequence、Docker/Testcontainers 與完整回歸 |
| Commerce／ADD | Desktop | Laptop | Booking、ADD execution、schema-free core、小型 focused gate、review 與 role-owned state-only handoff |

物理機器名稱不再等於永久 lane 名稱。啟用後的新 durable state 以 role 命名：

- `handoffs/upstream-integration-trigger-state.json`
- `handoffs/commerce-add-trigger-state.json`

舊 machine-named state 不交換內容、不改 writer，也不複製到另一台機器；啟用後保留為歷史
evidence，只讀不寫。

## 4. 啟用條件

以下條件必須同時成立：

1. `TR-CALENDAR-W10-MERGED=READY`，且產品 PR、state-only handoff、published SHA 都可由
   `origin/main` 驗證。
2. `TR-B4-FAKE-MERGED=MERGED`；此條件已由目前 main 滿足。
3. 沒有 active／granted schema token。
4. Calendar 與 Booking 舊 lane 已到 safe exit，所有 source、Flyway、Maven、Docker 與其他
   coordination claims 都已釋放。
5. 本準備文件已透過一般 PR 合併。
6. 另開極小的 state-only activation PR，回填 `effectiveFromSha`，把
   `TR-MACHINE-LANE-SWAP-MERGED` 設為 `READY`。
7. Activation PR 合併後重新 fetch；published SHA ancestry、assignment state 與兩份 role state
   必須一致。

Local PASS、commit、push、draft PR、聊天通知或一台機器自行修改 assignment 都不能啟用對調。

## 5. 兩階段發布

### 階段 A：Prepared coordination PR

本文件與新 runbook／state template 先合併。合併只代表方案可被兩台機器讀取，狀態仍是
`PREPARED_NOT_ACTIVE`，舊 ownership 不變。

### 階段 B：State-only activation PR

Calendar W10 完成並 hard-yield 後，由當時仍有效的筆電 Integration owner：

1. fetch `origin/main` 並驗證 W10 published evidence；
2. 確認 B4 merged、schema token 未 grant、雙方 claims 全數釋放；
3. 只更新 assignment 與兩份 role state，填入實際 `effectiveFromSha`；
4. 建立 state-only activation PR，不夾帶產品碼、migration、測試或下一 gate；
5. 合併後再次 fetch，發出 `TR-MACHINE-LANE-SWAP-MERGED` 的 `HARD_YIELD` receipt。

兩台機器收到 receipt 後仍須各自驗證 Git state，不能把通知當成證據。

## 6. Worktree 遷移規則

角色對調不搬移既有 worktree。每台機器在 ACK trigger 後都從已驗證的 `origin/main` 建立全新、
clean、role-based branch／worktree：

- Desktop Upstream／Integration：例如 `upstream/travel-3bb`。
- Laptop Commerce／ADD：優先候選 `commerce/add-execution-core-v1`。

禁止：

- 複製 `.git`、`.git/worktrees`、`target`、Docker volume、secrets 或 dirty 檔案。
- 從另一台機器的 local branch、patch、未提交 worktree 或聊天摘要建立新基線。
- reset、stash、clean 或重用目前來源不明／含使用者變更的 dirty root。
- 在新 worktree 驗證完成前取得 source／Flyway／Maven／Docker claim。

既有 dirty root 與舊 Codex worktree 一律 quarantine 為 read-only historical workspace；是否清理
是獨立工作，不能夾在角色對調 activation。

## 7. 啟用後的第一輪

### Desktop Upstream／Integration

- 先讀 assignment、registry 與 `desktop-upstream-integration-lane-runbook.md`。
- 使用者先前要求「Travel 3B-B 開始前一定確認」，所以 trigger ACK 不等於 3B-B 啟動授權。
- 使用者確認後，才從 matching `origin/main` 建立 Travel 3B-B 的獨立 gate。
- Calendar W10 只解除 recurrence dependency；3B-B 必須自行通過 focused、security-neighbor 與
  root regression gate。

### Laptop Commerce／ADD

- 先讀 assignment、registry 與 `laptop-commerce-add-lane-runbook.md`。
- 首選是 schema-free ADD Core，讓容易過熱的筆電避開長時間 Docker／RLS gate。
- B3-Durable 不自動轉給筆電；只有使用者另行確認、schema token 由新 Upstream owner 發出，
  且可安排不因過熱中斷的驗證窗口時才可開始。

## 8. 現有 session disposition

| Session／task | 行為 |
| --- | --- |
| Calendar W10 execution | 依舊 ownership 完成 W10、產品 PR、state-only handoff與 `TR-CALENDAR-W10-MERGED`；釋放 claims 後 hard-yield，不開始 W11 |
| Desktop Booking B4 | B4 已 merged；保持 safe exit，不開始 B3-Durable 或 ADD Core |
| Travel／Conversation Focus | 保留 3B-A 與 trigger 決策歷史；等待 role swap activation與使用者在 3B-B 前確認 |
| Conversation Focus F1–F5 | `COMPLETED_ARCHIVE`；不重新執行 sealed holdout |
| Calendar product-decision task | 歷史決策來源；不作為 W10 executor |
| Desktop environment／tooling | 只處理明確 tooling 工作；不取得產品 lane ownership |

通知 session 只傳遞 prepared plan 與 safe behavior。任何通知都不會取代 Git trigger、喚醒
consumer 開工或授予資源 claim。

## 9. 失敗與 rollback

- Activation 前發現 W10 evidence、SHA ancestry、claim release 或 state 不一致：保持
  `PREPARED_NOT_ACTIVE`，舊 ownership 繼續有效。
- Activation 後但任一新機器尚未 ACK：該機器不得寫入；另一機器也不得代替它跨 lane 開工。
- 若新 worktree preflight 失敗：標記 role state `BLOCKED`，停止並回報，不回用 dirty 舊樹。
- 若需要把 assignment 改回去，必須在兩個 role 的 safe exit 以新的 state-only PR 與新 trigger
  完成；不得直接覆寫歷史 state 或把 `effectiveFromSha` 倒退。

## 10. 完成定義

只有下列全部成立，角色對調才算完成：

- assignment status 是 `ACTIVE`，`effectiveFromSha` 非空且可由 `origin/main` 驗證；
- `TR-MACHINE-LANE-SWAP-MERGED=READY` 的 receipt 已送達使用者；
- Desktop 與 Laptop 分別 ACK matching published SHA；
- 新 role-based worktree clean，舊 worktree未被搬移或清理；
- 沒有任何產品 gate 因對調被冒稱 PASS。


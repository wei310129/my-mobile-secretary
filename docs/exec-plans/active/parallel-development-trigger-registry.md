# 多路並行開發 Trigger／Reminder Registry

> 狀態：`DOCUMENTED_NOT_PUBLISHED`
>
> Owner：筆電 Integration lane
>
> 適用範圍：Calendar、Travel、Booking、ADD execution、Conversation／LINE、Schema 與 Git/CI handoff

## 1. 目的

本文件把跨 lane 的「何時可以開始、何時必須停止、誰要通知誰、如何證明」變成固定協定。
任何 session 都不得假設另一個 Codex session 會自動收到訊息、重新 fetch、被喚醒或持續輪詢。

跨 session 交接採兩條通道：

1. **Durable state**：producer 更新自己擁有的 trigger state／active plan，經 PR 合併後可從
   `origin/main` 取得。
2. **Human-visible notification**：producer 依本文件格式通知使用者；需要使用者啟動、切換或
   授權另一條 lane 時，producer 必須結束當輪（hard yield）。

聊天通知不能取代 Git 證據；Git 狀態也不能取代對使用者的提醒。兩者都完成才算 delivered。

## 2. 狀態檔 ownership

| 檔案 | 唯一 writer | Consumer |
| --- | --- | --- |
| `handoffs/laptop-trigger-state.json` | 筆電 | 桌電與使用者 |
| `handoffs/desktop-trigger-state.json` | 桌電（Checkpoint B 後） | 筆電與使用者 |

不得由 consumer 修改 producer 的狀態檔。中央 trigger registry、雙機總體計畫、active index 與
current decisions 仍由筆電維護。桌電只在自己的 state file ACK 已消費的 laptop trigger。

## 3. Trigger 狀態與通知等級

Trigger 狀態固定為：

- `PENDING`：依賴尚未成立，不啟動 consumer。
- `BLOCKED`：已檢查但缺證據／權限／決策；必須附 blocker。
- `READY`：producer 證據已合併且可由 `origin/main` 取得。
- `ACKNOWLEDGED`：consumer 已 fetch 並驗證 READY。
- `IN_PROGRESS`：consumer 已在獨立 branch 執行。
- `PASS`：gate 證據完成，但若尚未 merge 仍不能解鎖下游。
- `MERGED`：gate 已進 `origin/main`，可觸發下游。
- `STALE`：base SHA、migration、typed contract 或 ownership 已被後續 main 變更淘汰。
- `CONSUMED`：一次性 trigger 已使用。
- `REVOKED`：producer 在使用前撤回 trigger。

通知等級：

| 等級 | 行為 |
| --- | --- |
| `HARD_YIELD` | 更新 durable state、通知使用者並結束當輪；不得先做下一個 gate |
| `NOTIFY_AND_CONTINUE` | 先用 commentary 通知，再繼續同 lane 的安全非重疊工作 |
| `DURABLE_ONLY` | 只回填狀態；不需要使用者立即切換 |

跨機器新 lane 啟動、一次性 schema token、新產品決策、destructive／external authority 一律
`HARD_YIELD`。一般同 lane 內部進度可用 `DURABLE_ONLY`；不確定時選較嚴格等級。

唯一窄例外是已核准、無 production ownership 移交且不取得 source／Maven／Docker／DB／Flyway／
LINE runtime claim 的 evidence-only lane。它仍須 producer Git READY 與 consumer ACK，但可用
`NOTIFY_AND_CONTINUE`；需要 credential／付費額度擴張、external mutation 或任何 production/resource
claim 時立即恢復 `HARD_YIELD`。目前只登錄 `TR-DESKTOP-ROUTE-BENCHMARK-START`，完整 allowlist 與
zero-mutation contract 見 W11-H active plan。

## 4. 交接投遞協定

Producer 必須依序完成：

1. 到達 safe gate：沒有 migration／外部 mutation 中途、未定位測試失敗或仍持有不可移交 claim。
2. 完成 gate 要求的 focused／full regression，明列未跑路徑。
3. 在 gate branch 更新 active plan evidence；commit／push／建立產品 PR，發
   `TR-PR-READY-FOR-REVIEW` 並 hard-yield。
4. 產品 PR 合併後 fetch `origin/main`，取得實際 `gatePublishedSha`。一個 gate 仍只有這一個產品 PR。
5. 建立極小的 state-only handoff PR，只更新 producer-owned state 與必要中央 trigger 狀態，
   並把 `publishedSha` 指向已存在於 main 的 `gatePublishedSha`。這不是第二個產品 gate，不得
   夾帶程式碼、migration 或下一輪內容。
6. State-only PR 合併後再次 fetch，驗證 `origin/main` 同時包含 gate SHA 與 READY／MERGED state。
   若沒有 Git authority，狀態只能是 `BLOCKED_NEEDS_PUBLISH`，不得通知 READY。
7. 對使用者發出 trigger receipt；若等級是 `HARD_YIELD`，立即結束當輪。

此兩階段避免在尚未知最終 merge／squash SHA 時自我參照。未來可用受保護的 CI 自動建立或合併
state-only handoff PR，但 automation 只能搬運已通過的 evidence，不能自行授權 schema、
destructive／external action 或產品決策。

Consumer 啟動後必須：

1. Fetch `origin/main`，讀 registry 與兩份 state file。
2. 驗證 trigger status、published SHA ancestry、依賴證據與自身 clean worktree。
3. 在自己 state file 記錄 `ACKNOWLEDGED`；再建立當輪 branch 並改成 `IN_PROGRESS`。
4. 任何證據不一致都標成 `STALE`／`BLOCKED`，通知使用者，不自行修 producer 文件。

禁止先開 consumer session 長時間等待。若使用者提早啟動，consumer 只做一次 read-only
preflight，回報 `BLOCKED` 後結束，不輪詢、不持有 branch／claim／Maven／Docker 資源。

## 5. 產品與依賴 Trigger 登錄表

| Event ID | Producer | READY 條件 | Consumer／動作 | 通知 |
| --- | --- | --- | --- | --- |
| `TR-DOCS-PUBLISHED` | 筆電 | 文件 checkpoint D 已進 `origin/main`，引用與 JSON 驗證通過 | 所有新 session 改讀雙機文件；不解鎖產品開發 | `DURABLE_ONLY` |
| `TR-DESKTOP-ROUTE-BENCHMARK-START` | 筆電 | W11-H plan／registry／laptop state 已進同一 `origin/main`；base SHA、evidence allowlist、forbidden paths、空 resource claims 與 external mutation count=0 可驗證 | 桌電 ACK 後建立 `desktop/calendar-w11-h-route-benchmark`，只寫 W11-H evidence allowlist；筆電繼續 eligible production gate | `NOTIFY_AND_CONTINUE` |
| `TR-DESKTOP-B3-START` | 筆電 | Checkpoint B／B2／V84 與必要依賴已合併；focused＋full regression 綠；`baseSha` 可取得 | 使用者啟動桌電；桌電建立 `desktop/booking-b3-core` | `HARD_YIELD` |
| `TR-B3-CORE-MERGED` | 桌電 | B3-Core PR 已合併、main ancestry 與 focused/full gate 可驗證 | 桌電下一輪建立 `desktop/booking-b4-fake` | `HARD_YIELD` |
| `TR-B4-FAKE-MERGED` | 桌電 | B4-Fake PR 已合併、9 組模式與 reconciliation/mutation-count gates 綠 | 若 schema token READY 做 B3-Durable；否則做 ADD Core | `HARD_YIELD` |
| `TR-CALENDAR-W10-MERGED` | 筆電 | Calendar W10 PR 已合併、完整 gate 綠 | 解鎖 Travel 3B-B 的「可開始驗證」；只讓 B3-Durable schema 成為候選，不自動 grant | `HARD_YIELD` |
| `TR-CALENDAR-W11-MERGED` | 筆電 | H1–H7、focused／neighbor／RLS／LINE／root、全新 24h／20 inbound monitoring 全綠；W11 產品 PR 與 matching state-only handoff 已在 `origin/main`；全部 claims 有 RELEASED receipt | 結束 W11 產品 gate 並更新中央文件；不得同一 receipt 自動切換 production ownership | `HARD_YIELD` |
| `TR-MACHINE-LANE-SWAP-MERGED` | 筆電 role-swap owner | `TR-CALENDAR-W11-MERGED` 可驗證，role-swap state-only handoff 已合併，producer/consumer ownership 與 claims 清單完整 | 筆電與桌電分別 fetch matching SHA 並 ACK；雙端 ACK 後桌電才取得後續 Upstream／Integration production ownership | `HARD_YIELD` |
| `TR-TRAVEL-3BB-MERGED` | 筆電 | Travel 3B-B 自己的 recurrence/copy/split propagation gate 已合併 | 後續 Travel 可繼續；不得冒稱 Wheels 6–7 PASS | `NOTIFY_AND_CONTINUE` |
| `TR-SCHEMA-B3-DURABLE-GRANT` | 筆電 | W10 merged、schema lane 空閒、B3-Core/B4-Fake merged、當下 latest migration 已鎖定 | 桌電建立 `desktop/booking-b3-durable` | `HARD_YIELD` |
| `TR-SCHEMA-B3-DURABLE-STALE` | 任一偵測者、筆電裁決 | reserved version 被 main 使用、base SHA 不再有效或 scope 改變 | 桌電不得跳號；筆電 revoke 並重發新 token | `HARD_YIELD` |
| `TR-B3-DURABLE-MERGED` | 桌電 | migration／RLS／retention／outbox gate 合併，token consumed | 筆電收回 schema lane；桌電等待 typed handoff或做 ADD Core | `HARD_YIELD` |
| `TR-TRAVEL-W6-W7-TYPED-MERGED` | 筆電 | Travel Wheels 6–7 typed transport／stay／traveller views 與 tests 已合併 | 桌電建立 `desktop/booking-b3-upstream-adapters` | `HARD_YIELD` |
| `TR-B3-UPSTREAM-MERGED` | 桌電 | adapters PR 合併且上游 contract tests 綠 | 才可宣稱完整 B3；B5 仍禁止，等待新使用者授權 | `HARD_YIELD` |
| `TR-ADD-CORE-MERGED` | 桌電 | ADD core PR 合併，Clock／prep window／dependency／uniqueness gates 綠 | 筆電建立 `laptop/add-execution-line-pilot-v1` | `HARD_YIELD` |
| `TR-ADD-LINE-MERGED` | 筆電 | flag-off、owner pilot、LINE/capability regression 與 full gate 合併 | 可提出 owner pilot 啟用決策；不得自行開 flag | `HARD_YIELD` |

`MERGED` 是跨 lane 解鎖的最低產品狀態，且 matching state-only handoff 必須已在 main。
Focused test PASS、local commit、已 push branch 或 draft PR 都不能讓 consumer 開始。

## 6. Git／CI／Ownership／Safety Trigger

| Event ID | 觸發條件 | 必須行為 | 通知 |
| --- | --- | --- | --- |
| `TR-MAIN-ADVANCED` | gate branch 建立後 `origin/main` 有新 commit | 在下一次寫入或測試前 fetch；若碰 ownership／migration／public contract，停止重驗 | `NOTIFY_AND_CONTINUE` 或 `HARD_YIELD` |
| `TR-PR-CI-FAILED` | focused、full regression、path guard、migration guard 任一失敗 | producer 保持 branch ownership、定位失敗；不得解鎖下游 | `HARD_YIELD`（無法定位或跨 lane 時） |
| `TR-PR-READY-FOR-REVIEW` | gate tests 綠、branch 已 push、PR 可 review，但尚未 merge | 回報 PR、證據與 ownership；停止，不開始下一 gate | `HARD_YIELD` |
| `TR-PR-MERGED` | gate PR 進 `origin/main` | 更新 producer state 為 MERGED，發出對應產品 trigger | 依產品表 |
| `TR-PATH-OWNERSHIP-CONFLICT` | PR／dirty tree 同時碰兩個 lane，或需要 consumer 修改 producer 路徑 | 停止寫入，列出最小介面需求；由筆電整合者／使用者裁決 | `HARD_YIELD` |
| `TR-MIGRATION-COLLISION` | duplicate version、existing migration mutation、token/base 不符 | 停止；禁止自行改號、改既有 migration 或合併 | `HARD_YIELD` |
| `TR-COMMON-CONFIG-REQUEST` | 桌電需要 `pom.xml`、shared config、central wiring／docs | 桌電只提需求；筆電以獨立 integration PR 處理 | `HARD_YIELD` |
| `TR-DIRTY-UNKNOWN-OWNER` | 出現來源不明或不在 allowlist 的變更 | 不 reset/stash/clean；停止並請使用者確認 owner | `HARD_YIELD` |
| `TR-DECISION-REQUIRED` | 新產品語意、提醒／buffer、排序、替代、資料保存或 rollout 選擇 | 列出最小互斥選項與影響，直接問使用者 | `HARD_YIELD` |
| `TR-HUMAN-AVAILABILITY-RISK` | 已觀察到 handoff／決策時點與使用者可用時間不合、切換過碎或 lane 長時間閒置 | 附具體觀察與成本，主動提出 1 個建議方案和替代方案；未核准前不改流程 | 阻擋時 `HARD_YIELD`，否則 `NOTIFY_AND_CONTINUE` |
| `TR-EXTERNAL-AUTHORITY` | provider、credential、sandbox/live、付費、個資、外部 mutation | 不執行；依 Booking skill 取得當輪明確授權 | `HARD_YIELD` |
| `TR-DESTRUCTIVE-AUTHORITY` | data/schema/code deletion、auto-cancel、不可回收 mutation | 附精確 target、備份／rollback／影響，再問使用者 | `HARD_YIELD` |
| `TR-CONTEXT-SAFE-EXIT` | gate 已落盤、claims 釋放、無 migration/unknown/test failure 中途 | 可建議 context 壓縮並附完整 handoff | `NOTIFY_AND_CONTINUE` |

## 7. Idle fallback 與效率規則

任何 lane 被依賴阻擋時，不得保持 session、Maven、Docker、branch 或 schema token空轉：

| Lane | 主要工作阻擋時的下一個 eligible 工作 |
| --- | --- |
| 桌電 B3-Core 前 | 無；回報 `TR-DESKTOP-B3-START` 尚未 READY 後結束 |
| 桌電 B4-Fake 後、schema 未 grant | `desktop/add-execution-core-v1` |
| 桌電 ADD Core 完成、schema／Travel typed 仍未 ready | 結束並回報依賴；不得侵入筆電 lane |
| 筆電 Checkpoint B 後 | 先 hard-yield 通知使用者；使用者恢復後繼續 Calendar W9-D/E／W10 |
| 筆電 W10 後 | hard-yield 通知 Travel/schema eligibility；恢復後執行使用者指定的最高優先 eligible gate |

提醒採「gate event」而不是固定時間輪詢。每個長工作單元至少在 commentary 說明目前 gate；
一旦觸發跨 lane READY，60 秒內能回報時應立即回報，不能為了順手完成下一輪而延後到多個 gate
之後。

## 8. Trigger receipt

所有對使用者的跨 lane通知使用下列欄位；不需要貼程式碼 diff：

```json
{
  "eventId": "TR-DESKTOP-B3-START",
  "notification": "HARD_YIELD",
  "producerLane": "laptop",
  "status": "READY",
  "publishedSha": null,
  "evidence": {
    "planGate": "Booking B2",
    "focusedTests": null,
    "fullRegression": null,
    "latestMigration": "V84",
    "pr": null
  },
  "consumerLane": "desktop",
  "consumerAction": "Paste desktop-booking-add-sol-medium-prompt.md and start desktop/booking-b3-core",
  "alternateAction": null,
  "claimsReleased": [],
  "blockers": [],
  "userDecisionRequired": [],
  "mustYieldNow": true
}
```

`READY` receipt 的 `publishedSha`、必要 tests 與 PR 不得為 null。`BLOCKED` receipt 可為 null，但
必須填 `blockers` 與 producer 下一步。

## 9. Human availability／睡眠與離線協定

人的注意力、睡眠與可切換 session 的時間是正式協作資源。Agent 必須根據明確資訊改善流程，
但不得從一段時間沒有回覆推斷使用者正在睡覺、同意操作或放棄決策。

可用模式：

| 模式 | 適用情境 | Agent 行為 |
| --- | --- | --- |
| `UNSPECIFIED` | 尚未提供作息 | 正常執行；發現實際摩擦才提建議，不猜 quiet hours |
| `ACTIVE` | 使用者可即時切 lane／回答問題 | 依 trigger 正常 hard-yield |
| `AWAY` | 使用者明示暫時離開或睡覺 | 只完成當前已授權、非 destructive／external 的 gate 到 safe exit；不得開始需要新決策的下一 gate |
| `QUIET` | 使用者要求停止新工作 | 釋放 claims、寫 handoff、停止；不得以「提高效率」擴張工作 |

以下任一可驗證現象出現時，agent 必須發出 `TR-HUMAN-AVAILABILITY-RISK`，主動提出改善建議：

- 兩次以上跨 lane hard-yield 都卡在相同人工切換或發布步驟。
- 使用者已明示即將睡覺／離線，而當前策略預計在期間產生必答問題。
- 一條 lane 可安全進行但因 handoff 太碎而反覆 idle。
- 多個低風險問題可批次確認，卻被拆成多次中斷。
- 完整回歸、PR review 或 schema handoff 的時點會讓另一台機器長時間無 eligible work。
- Agent 觀察到 plan 與實際工作樹／可用時間持續不符。

建議必須包含「觀察證據、效率損失、首選改善、替代方案、是否需使用者批准」。可建議但不可
自行啟用：

- 把非緊急問題批次到一個 review window。
- 在使用者離線前提前建立 safe checkpoint 與下一 lane READY receipt。
- 調整 gate 大小或順序，讓桌電改做已拍板的 schema-free ADD Core。
- 建立 GitHub merge/CI 通知、Codex automation 或固定 heartbeat。
- 記錄使用者自願提供的 quiet hours／timezone，讓通知延後但不讓 safety gate自動通過。

任何改善都不得靜默更改產品語意、提醒頻率、ownership、branch protection、schema owner、
destructive／external authority。使用者未回覆時採安全停下，不採預設同意。

Human-availability 建議格式：

```json
{
  "eventId": "TR-HUMAN-AVAILABILITY-RISK",
  "observedEvidence": [],
  "efficiencyCost": "",
  "recommendedChange": "",
  "alternatives": [],
  "requiresApproval": true,
  "safeBehaviorUntilDecision": "Finish only the currently authorized gate to a safe exit, then stop."
}
```

## 10. 使用者操作最小化

使用者只需要在 `HARD_YIELD` receipt 後做一件事：

- 啟動／恢復 receipt 指定的 consumer session；或
- 回答 `userDecisionRequired`；或
- 明示 producer 繼續其安全下一輪。

不要求使用者預先開著桌電 session。若未來另行實作 GitHub workflow、Codex automation 或 task
messaging，可用來加速通知，但它們只能傳遞 receipt，不能取代 consumer 的 Git revalidation，
也不能自動授權 schema、destructive action 或 external mutation。

## 11. 現有 Calendar session 的一次性 steering prompt

將下列訊息貼給目前正在執行 Calendar v2 的筆電 session，使本 registry 立即生效：

```text
請把目前 Calendar v2 工作接入 repo 的雙機 trigger protocol。先讀：

- docs/exec-plans/active/two-machine-parallel-development-plan.md
- docs/exec-plans/active/parallel-development-trigger-registry.md
- docs/exec-plans/active/laptop-integration-lane-runbook.md
- docs/exec-plans/active/handoffs/laptop-trigger-state.json

不要重做已 PASS wheels，也不要現在啟動桌電。繼續目前 Calendar gate；但只要 Booking B2
Checkpoint B 已安全合併到 origin/main、desktopStart 可更新為 READY，就必須完成
TR-DESKTOP-B3-START receipt，確認 published SHA 與測試證據，釋放 claims，停止當輪並明確告訴我：
「桌電可啟動 desktop/booking-b3-core」。不得先進下一個 Calendar gate。

我啟動桌電後會另行叫你繼續。之後到達 TR-CALENDAR-W10-MERGED、schema grant、Travel typed
handoff 或任何 registry 的 HARD_YIELD trigger，也使用相同協定停下通知。若無 Git publish
權限或證據未合併，只能回報 BLOCKED_NEEDS_PUBLISH，不得標 READY。

如果你觀察到我的睡眠／離線時間、人工切換頻率或兩台機器 idle 讓這套流程效率變差，請依
TR-HUMAN-AVAILABILITY-RISK 主動提出具體改善方案讓我評估；不要從未回覆推斷授權，也不要
未經確認自行更改開發順序、通知策略或 safety gate。
```

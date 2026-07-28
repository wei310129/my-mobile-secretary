# 桌電 Booking／ADD Lane Runbook

> Owner：桌電
>
> 建議模型：GPT‑5.6 SOL，reasoning Medium
>
> 狀態：`BLOCKED_PENDING_PUBLISHED_HANDOFF`
>
> 上位契約：`two-machine-parallel-development-plan.md`
>
> 觸發契約：`parallel-development-trigger-registry.md`

## 1. 啟動前置

桌電可以使用 Windows＋Docker，但第一波不需要啟動 Docker。開始任何寫入前必須全部成立：

1. `origin/main` 已包含總體 plan、兩份 runbook 與本文件。
2. Registry 與兩份 producer-owned JSON state 都存在。
3. 總體 plan 的 `desktopStart.status` 與 laptop state 的 `TR-DESKTOP-B3-START` 都是 `READY`，
   且指向同一個非空 `baseSha`／`publishedSha`。
4. `git merge-base --is-ancestor <baseSha> origin/main` 成功。
5. B2／V84、Booking domain／SPI／persistence／fake 基礎與測試都存在於該 SHA。
6. 新 clone／worktree是 clean，沒有筆電未提交檔案或來源不明 patch。
7. 沒有其他桌電 writable agent、Maven writer 或同路徑 branch 正在執行。

任一項不成立，只能回報 `BLOCKED`；不得重建 B2、複製筆電工作樹或先寫 B3。
Preflight 通過後，先在 `handoffs/desktop-trigger-state.json` 記錄 consumed laptop trigger 為
`ACKNOWLEDGED`，再建立 branch。

## 2. Branch 與交付順序

| 順序 | Branch | 允許內容 | 明確禁止 |
| --- | --- | --- | --- |
| 1 | `desktop/booking-b3-core` | provider-neutral search orchestration、ranking、transient progress／terminal ports、tests | DB、Spring controller、Calendar／Travel 修改、Intent、網路 |
| 2 | `desktop/booking-b4-fake` | V84 execution store、fake provider mutation orchestration、reconciliation、tests | 新 migration、公開 API／LINE、live provider |
| 3A | `desktop/booking-b3-durable` | 一次性 schema token 下的 Booking search durability | 無 token 寫 schema、修改既有 migration／上游 table |
| 3B | `desktop/add-execution-core-v1` | 無 schema 的 ADD core／ports／讀取 adapters、tests | Intent／LINE、通知 mutation、位置／天氣／交通 |
| 4 | `desktop/booking-b3-upstream-adapters` | Travel Wheels 6–7 typed views 的 Booking-side adapter | 修改 Calendar／Travel／Project |

3A 的 token 未 READY 時先做 3B；3A 與 3B 仍各自一個 branch／PR，不堆在同一 PR。每一列都等
前置 PR 合併並更新 `origin/main` 後再開新 branch。

每個 branch focused/full gate 綠、push 且 draft PR ready 時先發
`TR-PR-READY-FOR-REVIEW` 並 `HARD_YIELD`，不得直接開始下一 gate。PR 合併後恢復桌電 session，
fetch／驗證 main，再用只含 desktop state 的 state-only handoff PR 記錄實際 SHA。該 PR 合併後
把自己的 trigger state 視為 `MERGED`，再發產品表對應 receipt。

## 3. B3-Core 契約

只在 `booking/**` 新增純 Java 核心與 tests，不新增 migration、JPA、Controller、Intent、LINE、
provider SDK、網路、Docker 或 shared config。

核心行為：

- 同一 search 最多同時查兩個 provider source；一個 source 失敗、逾時或回傳壞資料，不得抹掉
  其他可信候選。
- 先排除 unavailable、expired 與 `IMPOSSIBLE`；不能把不可行方案包裝成便宜方案。
- 「最低總價」只從總成本完全已知的候選選出；unknown fee 的候選不可標成 cheapest。
- 「最彈性」比較 refund／change risk、限制與必要加購，不只看票面價格。
- 「最佳平衡」依序考量 feasibility、unknown／必要加購、退改風險，再比較總價；排序與 tie-break
  必須 deterministic。
- 同一候選若同時贏得多個 category，只回傳一筆並附多個 badge。
- Core 可定義 progress sink／terminal sink port，但在 B3-Durable 前不得宣稱 durable progress。
- 一次 search 最多一個 terminal result；timeout／partial source failure 的 terminal 語意由 Java
  決定並可重播驗證。

最低測試：

- 無庫存、全部 stale／expired、unknown fee、IMPOSSIBLE、相同總價 tie。
- 單一 provider 失敗、逾時、壞候選；另一 source 結果仍保留。
- 一個候選多 badge 且不重複。
- progress 次序、只出現一個 terminal、固定 `Clock` 重播結果一致。

B3-Core PASS 只代表 provider-neutral transient core；不得宣稱 B3 完整或已有可信即時庫存。

## 4. B4-Fake 契約

只使用 V84 與既有 `BookingExecutionStore`／SPI，完成：

- 3 種 confirmation mode × 3 種 substitution strength，共 9 組組合。
- 送單前重新驗證 quote／authorization，原子 claim，標記 dispatched，呼叫 fake，最後
  settle／reconcile。
- 價格、currency、條款、旅客、時間、地點或 provider capability 超出授權時 fail closed。
- 相同 request replay 不重複 mutation；測試斷言 fake provider mutation count。
- crash before send 可安全 retry；timeout／crash after send 進 `NEEDS_RECONCILIATION`，不得盲目重送。
- partial success 停止後續並保留成功項，只提出 cancel／replacement proposal，不自動取消。
- 全部 evidence 明示 `Environment=FAKE`，不建立或暗示 sandbox／live provider 結果。

最低測試包含 9 組模式、quote expiry、price/currency/terms/traveller mismatch、duplicate request、
crash before/after send、partial、reconciliation 與每條路徑 mutation count。

## 5. B3-Durable 契約

只有總體 plan 的 `schemaHandoff.status=GRANTED_ONCE` 才能開始。先 fetch 並驗證 `baseSha`、
observed latest migration 與 reserved version；任何不一致都停止請筆電重發 token。

只允許 Booking-prefixed additive schema：

- search job。
- candidate／offer binding。
- progress event。
- exactly-one terminal outbox。
- retention／cleanup 所需索引與狀態。

要求 workspace／actor scope、RLS、repository application filter、注入 `Clock`、idempotency 與
exactly-one terminal。未選取 search job／candidate 保留 7 天後刪除；被 authorization／execution
引用的 offer 依交易稽核保存。不得保存 raw provider payload、credential、旅客原文或付款資料，
不得修改既有 migration 或 Calendar／Travel／Task table。

## 6. B3-Upstream Adapters 契約

只有 Travel Wheels 6–7 typed view 明示 PASS handoff 後開始。Adapter 放在 Booking boundary，
只消費上游公開型別；不得改 Calendar／Travel／Project 來迎合 Booking。此 gate 通過前只能宣稱
B3-Core／B3-Durable 的各自範圍，不得宣稱完整 B3。

## 7. ADD execution core v1

新增 `execution/**` 與對應 tests，不新增 schema，不碰 Intent／LINE／capability catalog／shared
config。若需要上游資料，以 execution-owned port 與 immutable input record 隔離；可在
`execution/**` 寫 read adapter 消費已發布的 Task／Calendar query，但不可修改上游。

輸出固定：

- 0–1 個 `Now`。
- 0–1 個 `Next`。
- `Later` 只含 count 與可展開的穩定順序。
- 同一 item 不可同時出現在 Now、Next、Later。

排序規則：

1. 只納入 actor-adopted Calendar 與未完成 Task；dependency blocked task 排除。
2. Calendar 有明示 reminder／buffer 時優先使用；沒有時採開始前 15 分鐘的 display-only prep
   window，不建立 reminder 或 notification。
3. 進入 prep window 的固定 Calendar event 優先於 overdue high-priority Task。
4. 其後以時間壓力、deadline／overdue、priority 與 deterministic tie-break 選 Now／Next。
5. 沒有 urgent item 時，選一個未阻塞 open task並明示 `notUrgent=true`。
6. v1 不讀 location、weather、traffic，不執行 complete／snooze／can’t-do。

最低測試：

- 固定 `Clock`、明示 buffer、無 buffer 15 分鐘、prep window 邊界。
- imminent Calendar vs overdue high-priority Task。
- blocked dependency 排除、無 urgent fallback、全部 blocked／無資料。
- all-day Calendar 不被誤當固定 imminent event。
- Now／Next／Later 唯一性與 deterministic replay。

## 8. 檔案與共用邊界

桌電可修改：

- `src/main/java/com/aproject/aidriven/mymobilesecretary/booking/**`
- `src/test/java/com/aproject/aidriven/mymobilesecretary/booking/**`
- `src/main/java/com/aproject/aidriven/mymobilesecretary/execution/**`
- `src/test/java/com/aproject/aidriven/mymobilesecretary/execution/**`
- 本文件與 Booking active plan 中當輪 gate evidence
- 取得 token 後，恰一個新的 Booking-owned Flyway migration

桌電不可修改：

- Calendar、Travel、Project、Task／Reminder、Intent、LINE、capability catalog。
- `pom.xml`、shared config、中央 active index／current decisions、雙機總體與筆電 runbook。
- 既有 migration、非 Booking table、工具／啟停腳本。

若編譯或 Spring wiring 需要共用檔案修改，先把最小需求與候選介面回報筆電，不得越界處理。

## 9. Trigger consumer／producer 規則

- 每輪開始讀 registry、laptop state 與 desktop state；桌電只修改 desktop state。
- READY 聊天訊息若沒有 matching Git state／published SHA，一律視為 `BLOCKED`。
- B3-Core merged 才解鎖 B4-Fake；B4-Fake merged 後：
  - schema token 已 READY：可做 B3-Durable。
  - schema token 未 READY：改做 ADD Core，避免 idle。
- Travel Wheels 6–7 typed trigger未 READY 時，不開 B3-Upstream Adapters。
- ADD Core merged 後發 `TR-ADD-CORE-MERGED` 並停下通知使用者恢復筆電 LINE lane。
- schema token stale 時不得自行跳號；記錄 blocker、通知使用者並釋放資源。
- 沒有 eligible gate 時結束 session，不輪詢、不預占 branch／Maven／Docker。
- 若觀察到使用者睡眠／離線、切 lane 負擔或 handoff 粒度讓桌電反覆 idle，依
  `TR-HUMAN-AVAILABILITY-RISK` 主動提案，但不自行改順序或 safety gate。

## 10. 驗證、PR 與停止規則

- Maven 一律使用 `scripts\mvn-safe.ps1`；同一桌電一次一個 writer，不預設 clean。
- 每個 PR 先跑 focused tests；完成一個 gate 且準備 merge 前跑完整
  `scripts\mvn-safe.ps1 test`。
- 不啟動 live runtime、provider、Playwright 或付費服務；B3-Core／ADD 不需要 Docker。
- 一個 gate 一個 PR，branch 必須更新到最新 `main`；可 commit、push、開 draft PR，但不可自行
  merge。
- 禁止任何 `git diff`；依 repo 規則用精準讀取、Spotless、compile、tests 與 status 驗證。
- 遇到新產品選擇、ownership／schema 衝突、缺 typed handoff、未知 external state、破壞性操作
  或需要擴張 scope，立即停止並直接問使用者。

每個 gate 回報 base SHA、修改檔案摘要、測試數、external environment、mutation count、
idempotency／RLS／Clock 證據、未測項、PR、下一步與風險。

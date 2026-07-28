# GPT‑5.6 SOL Medium — 桌電 Booking／ADD Lane 啟動 Prompt

> 使用方式：等筆電把 `two-machine-parallel-development-plan.md` 的
> `desktopStart.status` 更新為 `READY` 並推送到 `origin/main` 後，將下方 prompt 完整貼給桌電
> Codex。若現在先貼，agent 應只完成 read-only preflight 並回報 `BLOCKED`。

```text
你現在在桌電接手 my-mobile-secretary 的 Desktop Booking／ADD lane。使用 GPT‑5.6 SOL，
reasoning Medium。請直接執行 repo 內已拍板計畫，不重寫規格，也不要開始計畫外工作。

Goal

先做 read-only preflight。只有 laptop 已發布 Checkpoint B，且
docs/exec-plans/active/two-machine-parallel-development-plan.md 的
desktopStart.status=READY、baseSha 可由 origin/main 取得時，才從該 SHA 建立
desktop/booking-b3-core，實作並驗證 B3-Core。這一輪只做 B3-Core；不得順手進 B4。

Required context

依序完整讀：

1. 根 AGENTS.md 與任何更近的 AGENTS.md。
2. docs/agent-context/index.md。
3. docs/agent-context/execution-plan-policy.md。
4. docs/decisions/current.md。
5. docs/exec-plans/active/index.md。
6. docs/exec-plans/active/two-machine-parallel-development-plan.md。
7. docs/exec-plans/active/parallel-development-trigger-registry.md。
8. docs/exec-plans/active/handoffs/laptop-trigger-state.json。
9. docs/exec-plans/active/handoffs/desktop-trigger-state.json。
10. docs/exec-plans/active/desktop-booking-add-lane-runbook.md。
11. docs/exec-plans/active/booking-commerce-sol-medium-development-test-plan.md 的現況、B3、
   resource coordination、tests 與 handoff 章節。
12. .agents/skills/develop-and-validate-booking-execution/SKILL.md 及它要求的 references。

Preflight

- 先回報本輪目標、預計檢查的 1–3 個資料夾、候選檔案、non-goals 與驗證方式。
- fetch origin，檢查 working tree clean、目前沒有另一個 writable agent／Maven writer。
- 驗證 desktopStart.status、laptop state 的 TR-DESKTOP-B3-START、baseSha／publishedSha、
  origin/main ancestry、B2/V84 與 Booking 基礎是否真的存在。
- 只允許使用 Git 可取得的 commit；不得複製、猜測或重建筆電未提交內容。
- 禁止 git diff、reset、checkout 還原、stash、clean 或改寫 history。
- 若任一 preflight 不成立，停止，不建立產品 branch、不寫檔，只用繁中回報 BLOCKED、證據與
  筆電必須補的 handoff；不要保持 session 輪詢等待。
- Preflight 通過後，先在 desktop trigger state ACK TR-DESKTOP-B3-START，再建立 branch。

B3-Core scope

Preflight 全部通過後，從文件指定 baseSha／最新 origin/main 建立
desktop/booking-b3-core。只修改：

- src/main/java/com/aproject/aidriven/mymobilesecretary/booking/**
- src/test/java/com/aproject/aidriven/mymobilesecretary/booking/**
- desktop runbook 或 Booking active plan 的 B3-Core gate evidence

實作 provider-neutral、純 Java availability orchestration、deterministic ranking、transient
progress／terminal ports 與 focused tests：

- 同一 search 最多兩個 provider source 並行；單一 source failure／timeout 不抹掉其他可信結果。
- 排除 unavailable、expired、IMPOSSIBLE。
- unknown fee 不可標為 cheapest；最低總價只選 total fully known。
- most flexible 比較 refund/change risk；best balance 依 feasibility、unknown/add-on、risk、price。
- 同一 winner 若跨多 category，只回一筆並附多 badge。
- 固定 Clock、deterministic tie-break，一次 search 最多一個 terminal。
- 測無庫存、stale、unknown、impossible、tie、provider failure/timeout、progress、terminal。

Boundaries

- 不新增或修改 Flyway、DB/JPA、Spring controller、Intent、LINE、Calendar、Travel、Project、
  Task/Reminder、capability catalog、pom、shared config、tooling scripts。
- B3-Core 不呼叫真實網路，不使用 Docker、provider credential、sandbox/live API、Playwright，
  外部 mutation 必須是 0。
- 不宣稱 durable progress、可信即時庫存或完整 B3。
- 同一桌電只有你一個 mutating agent與一個 Maven writer；不要 spawn mutating subagent。
- Maven 只透過 scripts\mvn-safe.ps1，先 focused gate，不把 clean 當第一步。
- 保留所有非本 lane 變更；發現不明 dirty content 立即停止。
- 遵守 trigger registry。PR ready、PR merged、schema stale、跨 ownership、決策、external／
  destructive authority 都依指定通知等級處理；HARD_YIELD 必須結束當輪。

Questions

已在文件拍板的決策直接執行，不要重問。遇到新的產品語意、跨 ownership、缺上游 typed
contract、schema／migration、共用設定、外部服務、真實個資、destructive action 或無法定位的
test failure，必須直接問我，不可猜。

如果你觀察到我的睡眠／離線、人工切換頻率、handoff 粒度或兩台機器 idle 讓實際效率下降，
依 TR-HUMAN-AVAILABILITY-RISK 主動提出具體改善方案讓我評估；不要從沒有回覆推斷授權，也
不要自行改 quiet hours、開發順序或 safety gate。

Done when

- B3-Core 最小實作與 focused tests 通過。
- 使用固定 Clock 且排序／terminal 可 deterministic replay。
- 外部 environment=NONE、external mutation=0。
- 以繁中回報 base SHA、branch、修改範圍、測試 passed/failed/errors/skipped、未跑 gate、
  風險與下一步。
- Gate 綠後更新 desktop trigger state 為 PASS，可 commit、push 並建立 draft PR，發出
  TR-PR-READY-FOR-REVIEW 後 HARD_YIELD；不可自行 merge，也不可開始 B4。
- 產品 PR 日後合併時，恢復本 session、fetch 並驗證 origin/main，取得實際 gate SHA；建立只更新
  desktop trigger state 的 state-only handoff PR。該 PR 合併後再確認
  TR-B3-CORE-MERGED=MERGED、發出對應 receipt 並 HARD_YIELD，等待我啟動下一個 gate。
```

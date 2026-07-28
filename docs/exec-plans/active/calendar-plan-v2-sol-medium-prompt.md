# Sol Medium 啟動提示詞：Calendar v2／行程圖系統

將下方提示詞完整貼給使用 GPT-5.6 Sol、推理模式 Medium 的 Terra。它引用 repository 內唯一執行
計畫，不需要再附整份計畫內容。

---

你現在接手 `my-mobile-secretary` repository 的「Calendar v2／行程圖系統」track。使用 GPT-5.6
Sol、推理模式 Medium 實際開始開發；不要只做評估、重寫規格或另建平行 plan。

唯一執行契約：

- `docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md`

雙機 ownership 與 trigger 契約：

- `docs/exec-plans/active/two-machine-parallel-development-plan.md`
- `docs/exec-plans/active/parallel-development-trigger-registry.md`
- `docs/exec-plans/active/laptop-integration-lane-runbook.md`
- `docs/exec-plans/active/handoffs/laptop-trigger-state.json`

長期邊界與目前決策入口：

- 根 `AGENTS.md`
- `docs/agent-context/index.md`
- `docs/agent-context/execution-plan-policy.md`
- `docs/architecture.md`
- `docs/decisions/current.md`
- `docs/test-strategy.md`

使用者已確認主計畫 D01–D61 與第 16 節全部產品決策，不要重新詢問或自行改選。尤其必須保留：

- 新 `calendar` bounded context 最終完全取代 legacy `schedule`；開發期隔離並存，不 migration
  測試資料、不 dual-write、不做長期 unified model。
- plan＋一層 activity＋多個 time nodes；criticality 與 adjustability 分離。
- 任意重疊可建立；只有 actor 已採用的節點限制本人路徑，不可達時保留資料並警告。
- 建立、共享、participation、adoption、busy impact、reminder 是不同操作；不得把任何人綁死。
- 多群組 sharing、報名／名額／候補、權威變更、recurrence、Task／Knowledge binding、ICS、
  search／online link／attachment 的完整語意以 D01–D61 為準。
- 第一個 release 是 Backend REST v2＋LINE；原生 iOS／EventKit client 另案。EventKit／ICS 不是
  source of truth。
- Travel Project 舵輪 1、2、3A 已完成；3B 維持 `BLOCKED_BY_CALENDAR_V2`。不得修改
  `schedule_item` ownership、實作 project-scoped legacy Schedule CRUD，或啟動 Travel 3B。

開始方式：

1. 先完整讀根 `AGENTS.md` 與 `docs/agent-context/index.md`，再依路由讀本提示詞列出的文件。因本
   track 會影響自然語言、LINE、引用上下文與使用者可見回覆，必須完整讀
   `.agents/skills/develop-and-evaluate-conversation-capability/SKILL.md` 及其指定的必要 references。
2. 讀 active index 與主計畫最新 machine-readable handoff，從目前第一個未 PASS wheel 恢復；
   不重做已 PASS wheel。若 repository 尚無任何 gate evidence，才載入主計畫的 0–2、5、7–8、
   10 節「舵輪 0」、11–12、14–16 節從舵輪 0 開始。進入後續舵輪時再讀該舵輪及直接相依章節。
3. 第一則 commentary 先用繁體中文回報：本輪唯一目標、預計檢查的 1–3 個資料夾、候選檔案、
   dirty worktree 保護方式與驗證命令。
4. 先執行 `git status --short`，辨認並保留所有既有修改與 untracked files。禁止 reset、checkout、
   stash、clean、覆寫、搬移或順手格式化使用者／其他開發線的變更。
5. 若是全新計畫才從「舵輪 0：決策 freeze 與 scenario manifest」開始，不得跳到 schema 或
   production code；已有 PASS handoff 時依 current wheel 恢復。舵輪 0 allowlist 只有主計畫列出的
   正式文件，以及必要時 `src/test/resources/calendar/` 下不含個資、原始對話或 sealed oracle 的
   machine-readable metadata。
6. 只有執行舵輪 0 時，才必須核對 D01–D61、C01–C145、active index、current decisions、
   Travel 3B stop 與公開
   holdout variation axes，並跑以下 baseline：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,ScheduleInsightServiceTest,FamilyMessageServiceTest,DailyScheduleQueryTest,WorkspaceRlsIntegrationTest" test
```

7. 把實際命令、exit code、tests/failures/errors/skipped、未測路徑與 dirty baseline 回填主計畫。
   全新計畫在舵輪 0 gate 全過後才開始舵輪 1；既有計畫沿最新 handoff 繼續。不要在每輪後詢問
   一般性的「要不要繼續」，但 trigger registry 的 `HARD_YIELD` 是硬性停止條件。

硬性執行規則：

- LLM 只做 structured language understanding／expression；Java 決定 target、authorization、
  ZoneId／Instant、offset、dependency、recurrence、state transition、capacity、route、reminder、
  idempotency 與 mutation。不得把 LLM 當業務執行器。
- Controller 只處理 protocol／DTO；domain 不依賴 Web/API、repository 或 provider。
- 所有時間與 timeout 使用注入的 `Clock`。所有 schema 只用 forward-only Flyway；維持
  `spring.jpa.open-in-view=false`。
- 任何 actor/workspace-owned table 必須同輪完成 composite ownership、application filter、
  PostgreSQL runtime-role RLS、同 workspace 不同 actor、跨 workspace與適用 background worker
  integration tests。
- 舵輪 1 不得建 migration；舵輪 2 才能透過 repository coordinator 預約當下可用 Flyway version。
  不得預先猜版本，也不得修改既有 migration。
- 新 API 使用獨立 `/api/v2/calendar` namespace；新 Intent 必須同批新增 domain handler、
  `conversation-capabilities.txt` entry、actual-entry regression 與鄰近 Task／Knowledge／legacy
  Schedule 反例。
- 新增使用者可感知 domain event 時，同批接通通用 LifeRecord／tag graph；純 view、binding、
  projection rebuild、queue claim、ICS preview/export 與開發回饋不是生活事件。
- 先寫 red test，再做最小實作；不得用 exact-phrase hard-code、prompt patch、降低 threshold、
  刪難例、放寬 assertion、更新 expected 遷就 bug，或用 live model 取代 deterministic gate。
- repair case 通過後升為 permanent regression；sealed holdout 必須由獨立 evaluator 產生與保管。
  Builder 不得看 oracle、自產自評或把 holdout 原句寫入 public plan。
- root Maven lifecycle 一律透過 `scripts/mvn-safe.ps1`，預設不 clean。只有證明舊 class／產生碼
  污染且確認無並行 Maven 時才處理；完整回歸依主計畫 gate 執行。
- 不執行任何 `git diff` 系列指令，不把 diff 或大量原始輸出送進對話。用精準 `rg`、Spotless、
  編譯、測試、結構化摘要與 `git status --short` 驗證。
- 修改文字檔一律使用 `apply_patch`。不要提交、push、開 PR、安裝新服務或連接付費 provider，
  除非使用者另外明確授權。
- 一般產品開發不得順手修改 scripts／tooling；觀察到的需求只登記
  `docs/tooling-backlog.md`，留待工具專用 session。
- 不宣稱已訂票、改票、報到、付款、通知外部人士、寫入 Apple Calendar 或抓取外部 URI。

每輪執行契約：

- 開始前在主計畫回填 current wheel、唯一目標、non-goals、候選 production/test/migration 檔案、
  resource claims 與 validation。
- 只搜尋任務直接相關的 1–3 個資料夾；有明確 dependency 才擴大並先說明。
- 遵守主計畫第 14 節資源取得順序、owner/generation fencing、stale-owner audit、owner-scoped
  cleanup 與 machine-readable handoff；timeout／heartbeat 過期不能直接 takeover。
- 每個 gate 保存實際命令、exit code、passed/failed/skipped、performance sample、live path 是否
  執行、殘留與風險。未跑的路徑明說未跑，不得推定通過。
- 跨 intent、calendar、planner、reminder、account/RLS 三個以上主要模組或累積多個 migration 的
  階段收尾，必須跑完整 `mvn-safe.ps1 test`。
- 使用者可見行為另以完整、口語、省略、修正、引用、typo、replay、同 actor 跨 channel、
  同 workspace 不同 actor、跨 workspace、information leak與 latency case 驗證。
- 每輪開始讀 laptop trigger state。Checkpoint B、Calendar W10、schema grant／stale、Travel typed
  handoff、PR ready／merged 或其他 registry `HARD_YIELD` event 成立時，先更新 durable state、
  確認 `origin/main` 可取得 published SHA、釋放 claims，發出 receipt 並結束當輪。
- `TR-DESKTOP-B3-START` READY 時必須明確告訴使用者
  「桌電可啟動 `desktop/booking-b3-core`」，不得先進下一個 Calendar gate。使用者啟動桌電後會
  另行要求本 session 繼續。
- 不假設桌電 session 已開著、會自動收到訊息或重新 fetch；也不要求使用者預先開 session 等待。

停止條件：

- 需要改變 D01–D61、提醒筆數／頻率／緩衝、權限、參加／略過、recurrence、sharing、ICS 或
  cutover 語意。
- migration version／dirty file 與其他開發線無法安全協調，或同一檔案存在無法保留的重疊修改。
- 需要新的付費／外部 provider、真實個資／位置資料、Apple full calendar access、外部交易或新權限。
- RLS、authorization、idempotency、migration、destructive confirmation、information leak 或
  hard gate 失敗且尚未定位。
- 任何 `HARD_YIELD` trigger；local PASS／commit／push／draft PR 不能冒充跨 lane READY。
- 已觀察到使用者睡眠／離線、人工切換過碎或 lane idle 造成效率損失時，依
  `TR-HUMAN-AVAILABILITY-RISK` 主動提出具體改善建議。不得從未回覆推斷授權或自行設定 quiet hours。
- 要進入舵輪 12 legacy retirement。啟動提示詞不授權刪除 legacy code/schema/data；必須先提出
  精確 code／API／Intent／table／FK 清單、備份與復原證據，再取得使用者當輪 destructive approval。

主計畫的 context-compression points 只是候選建議。依根 `AGENTS.md` 自主判斷；適合時告知使用者
「現在是適合壓縮 context 的時機」並附可獨立續作摘要，不得在未提交決策、migration 中途、未定位
測試失敗或仍直接依賴大量上下文時壓縮。

現在先依 active index 與最新 handoff 判斷 current wheel，再從第一個未 PASS gate 繼續實際工作。
不要重做已 PASS wheel；遇 trigger registry 的 `HARD_YIELD` 必須停止並通知使用者。

---

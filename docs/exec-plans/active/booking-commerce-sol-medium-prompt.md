# GPT‑5.6 SOL Medium — Booking／Commerce Execution 啟動提示詞

你現在接手 `my-mobile-secretary` 的 Booking／Commerce Execution track，使用 GPT‑5.6 SOL、
推理模式 Medium 實際開發。唯一執行契約是：

- `docs/exec-plans/active/booking-commerce-sol-medium-development-test-plan.md`

不要重寫規格、另建平行計畫或自行改變已拍板產品語意。

## 本次只執行 B0

B0 未 PASS 不得進 B1。先完整讀取：

1. 根 `AGENTS.md`。
2. `docs/agent-context/index.md`。
3. `docs/agent-context/execution-plan-policy.md`。
4. `docs/exec-plans/active/index.md`。
5. `docs/decisions/current.md`。
6. Calendar／Travel active plan 的最新 status、current wheel、resource coordination、handoff 與直接依賴章節。
7. `.agents/skills/develop-and-validate-booking-execution/SKILL.md` 及它要求的 references。
8. 若涉及 Intent、LINE 或公開回覆，再完整讀
   `.agents/skills/develop-and-evaluate-conversation-capability/SKILL.md` 及必要 references。

先執行 `git status --short`，禁止任何 `git diff`。目前工作樹可能含 Calendar v2、Travel 及其他
session 的大量既有變更；全部視為他人所有。禁止 reset、checkout、stash、clean、還原、搬移、
覆寫、全域格式化或把它們納入本線。

第一則 commentary 必須用繁中回報本輪唯一目標、預計檢查的 1–3 個資料夾、候選檔案、明確
non-goals、dirty baseline 保護方式、resource claims、驗證命令與主機資源預算。

## 並行開發與有限主機

主機資源有限。預設只有你一個 mutating agent、一次一個 Maven writer、一組 Testcontainers／Docker
重型 gate、一個 Playwright browser context 與一個 sandbox E2E。完整 Maven regression、Duffel
sandbox E2E、Playwright 不得互相並行。Mutating subagent 預設關閉；唯讀 subagent 也先從一個開始。

每輪修改前先取得 repository coordinator claim。所有資源一次宣告，使用 coordinator canonical rank
排序；不得自行反序取得。Timeout 不構成 takeover 權限，owner／generation／receipt 不可驗證時 BLOCKED。

Calendar Wheel 8 與 Travel 3B-A 未形成穩定 handoff／full regression 前：

- B0 只處理 Booking 專屬文件、skill、協調矩陣與 allowlist。
- 日後 B1 即使獲准，也只能新增 Booking 純 domain、SPI、fake 與 tests。
- 不得碰 Calendar、Travel、Intent、Notification、Knowledge、StoredMedia、LifeRecord、migration、
  shared runtime 或既有測試。

Calendar Wheel 10 只阻擋 recurring ownership propagation，不阻擋 one-off Booking foundation。

## 開發不變量

- 嚴格依 B0–B9 順序，先建立失敗測試，再做最小實作。
- LLM 只負責 structured understanding／expression。
- Java 負責價格、庫存、時間、授權、替代、狀態機、actor/RLS、冪等與外部 mutation。
- 行程確認不等於購買授權。
- 沒有 fresh quote、有效 `PurchaseAuthorization`、actor/workspace 驗證與 provider capability，
  不得 hold、book、pay、change 或 cancel。
- Provider timeout 或結果未知必須先 reconcile，禁止盲目重送。
- 部分成功時停止後續並保留成功項；沒有新授權不得自動取消。
- 沒有可信 provider 時明說「目前無法確認」，使用官方 deep link／人工接手，不可冒稱有票。
- 卡號、CVC、密碼、OTP、3DS、CAPTCHA、browser auth state、完整護照、secret、raw provider
  payload 不得進 prompt、DB、log、LifeRecord、handoff 或版控。
- Hosted/tokenized payment 與使用者接管是唯一付款／驗證路徑，不得繞過安全機制。

不得執行 live book／cancel、真實付款、付費 provider、真實個資處理、服務安裝、commit、push
或 PR，除非使用者在當輪另行明確授權。

## Subagent

只有計畫明確允許、interface 已 freeze、使用獨立 worktree、coordinator 核發不重疊 source claim
且資源預算允許時，才可開 mutating subagent。共享 worktree 只允許唯讀研究／驗證。Subagent 不得
再 spawn。主 agent 永遠獨占 migration、active docs、current decisions、architecture、capability
catalog、共用 integration／outbox、合併、完整回歸及所有 live side effect。

## 驗證與回填

每輪只跑該輪最小 deterministic gate；階段出口才跑完整 Maven regression。所有 Maven lifecycle
透過 `scripts/mvn-safe.ps1`，不得把 clean 當第一步。

每輪回填實際修改檔案、命令、exit code、passed／failed／errors／skipped、external environment、
masked evidence、mutation／idempotency／RLS／privacy／quoted context／progress／latency、
未測路徑、failed／skipped gates、claims release、風險、下一步與 machine-readable handoff。

Focused tests 不代表 release 完成。Duffel test mode 是第一版外部交易 release gate；沒有使用者另行
批准不得改用 live 交易。Sandbox 通過不得宣稱 production 支付／退款已驗證。

遇到產品語意變更、資源衝突、migration 衝突、provider unknown／partial／pending、hard gate 未定位
失敗、live 交易、真實個資、新外部權限、付費服務、destructive cleanup，或 Playwright 的 CAPTCHA／
OTP／3DS／價格／條款／DOM 不符時，立即停止並精確回報。

## Context 壓縮

計畫中的壓縮點只是候選。只有決策與證據已落盤、所有 claims 已釋放、沒有 migration／外部交易
中途、provider unknown／partial 或未定位測試失敗時，才說：

`現在是適合壓縮 context 的時機`

並附完整自包含 handoff：phase、決策、不變量、dirty baseline、修改檔案、resource owner／generation、
migration reservation、測試結果、external verification、side-effect state、failed／skipped gate、
剩餘工作、下一步、風險、blocker、使用者決策與 cleanup disposition。

現在只從 B0 開始；B0 未 PASS 不得進 B1。


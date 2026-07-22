# Terra High 啟動提示詞：開發 Session 自協調 Pipeline

> 已歸檔：所有 Phase gate 已完成。本提示詞保留作歷史 handoff，不得用來重新開啟同一 track。

將下方提示詞完整貼給 Terra High。它引用 repository 內唯一執行計畫，不需要再附整份計畫內容。

---

你現在接手 my-mobile-secretary repository 的「開發 Session 自協調 Pipeline 與殘留治理」工具專用
track。請實際開始開發，不要只做評估、重寫計畫或再產生另一份平行 plan。

唯一執行契約：

- docs/exec-plans/completed/development-session-coordination-pipeline.md

長期邊界：

- docs/architecture.md 第 36 節
- docs/decisions/current.md
- docs/agent-context/execution-plan-policy.md

開始方式：

1. 先讀根 AGENTS.md 與 docs/agent-context/index.md，再讀上述唯一執行計畫的 0、1、3、4、6、8、10、
   11、12 節；進入各 Phase 前再讀直接相關的 2、5、7、9 節項目。不要預先全文掃描其他長文件。
2. 第一則 commentary 先用繁體中文回報本輪目標、只會檢查的 1–3 個資料夾、候選檔案與驗證方式。
3. 先執行 git status --short，保留所有 unrelated dirty／untracked work。禁止 reset、checkout、stash、
   clean、覆寫或順手格式化他人的變更。
4. 從計畫 Current phase「Phase 0：設計 freeze 與安全邊界」開始。第 1.2 節是已選定的起跑預設；
   不要為這些方向重新詢問使用者。只有 prototype 證明在 Windows／Codex sandbox 不可行時，才選擇
   等價且更安全的 fallback，並把證據與決策回填計畫。
5. Phase 0 gate 通過後，直接開始 Phase 1 coordinator kernel；之後依序持續執行下一 Phase，直到遇到
   真正需要新權限、destructive approval、無法安全推進的 blocker，或全部 Phase 完成。不要在每個
   Phase 後停下詢問一般性的「要不要繼續」。

硬性執行規則：

- 這是 tooling-only track。產品 Java/domain、主 Flyway schema 與使用者產品語意不在範圍。
- internal/ai-dispatcher 有獨立 AGENTS.md；只有 Phase 3 明確需要 durable pause／drain contract 時才
  依其路由擴查。不得直接改 Dispatcher DB row、釋放 claimed event 或清 unknown active run。
- Phase 1 前不得操作 live Docker；Phase 3 前不得切換 live dev lifecycle。先以 fake
  Docker／Maven／process adapters 完成 concurrency、crash、CAS 與 cleanup oracle。
- 不得自動刪除 shared dev volume、Flyway history、Git/source、secrets、Redis shared keys 或
  Dispatcher uncertain state。禁止 docker compose down -v、Docker prune、Redis FLUSHALL、Flyway
  clean／repair 與例行 Maven clean。
- 所有 root Maven lifecycle 依根 AGENTS.md 透過 scripts/mvn-safe.ps1；不直接執行 root mvnw。
  Dispatcher build 依其獨立規範，直到 coordinator-aware adapter 接手。
- 不執行任何 git diff 系列指令，也不要把 diff 或大量原始輸出送進對話。用精準搜尋、Spotless、
  編譯、測試、結構化摘要與 git status --short 驗證。
- 修改檔案一律使用 apply_patch。不得提交、push、開 PR 或安裝新服務，除非使用者另有明確要求。
- 若使用 sub-agent，只分派具體、互不重疊的唯讀或檔案範圍；所有 agent 共用工作區，主 agent 必須
  避免同檔競寫並整合結果。

Phase 執行契約：

- 每個 Phase 開始前，更新主計畫的 Current phase、預計修改檔案與本輪 validation。
- Phase 0 必須 freeze resource keys、RW／counting compatibility、canonical total order、machine-global
  與 repo-common registry roots、Windows lock backend、cleanup classes、outcome／exit codes、fake
  adapters及既有入口 rollout contract。
- 先寫會失敗的 deterministic concurrency／failure-injection test，再接 adapter；live smoke 永遠在
  fake gate 之後。
- 每個 hard gate 都回填實際命令、exit code、通過／失敗數、skipped live paths、residual、
  cleanup disposition、風險與下一步。
- 任何 timeout／heartbeat expiry 只能觸發稽核，不能直接 takeover。所有 cleanup 必須驗證
  owner／operation／generation，uncertain 一律 BLOCKED。
- 優先完成 P0：dev lifecycle umbrella coordination、Maven coverage closure、Docker doctor/recovery、
  atomic state／service-generation logs、read-only doctor 與 receipt。
- 維持既有 dev-start／stop／restart／status／mvn-safe 相容入口，讓它們成為 coordinator adapters；
  不建立第二套互相繞過的 lifecycle。

完成標準：

- 不只程式碼存在；計畫內對應 Phase 的 hard gates 必須有實際證據。
- 下一個 session 能透過 doctor 與 machine-readable receipt 判斷 READY、DEGRADED、BUSY、
  RECOVERY_REQUIRED 或 BLOCKED，且知道每個 residual 是 removed、kept-shared、kept-persistent、
  quarantined 或 unknown。
- Existing repository-owned Maven、Compose、lifecycle、Spotless entrypoints 不得有未記錄 bypass；
  unmanaged IDE／direct command 必須被偵測並 fail closed。
- 所有文件、operator command、exit code、cleanup class 與實際行為一致。

主計畫第 11 節是壓縮候選點而非強制時機。依根 `AGENTS.md` 綜合開發品質、開發效率與 token 效率
自行判斷，可提前、延後、跳過或新增候選點；決定建議壓縮時，告知使用者「現在是適合壓縮 context
的時機」並附上可獨立續作的 continuation summary，不得遺失決策或用壓縮跳過 gate。

現在先從 Phase 0 開始工作，完成 gate 後直接進 Phase 1，不要只回覆一份新計畫。

---

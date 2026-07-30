# 筆電 Integration／Schema Lane Runbook

> Owner：筆電
>
> 狀態：`ACTIVE_LEGACY_UNTIL_TR_MACHINE_LANE_SWAP_MERGED`
>
> 上位契約：`two-machine-parallel-development-plan.md`
>
> 觸發契約：`parallel-development-trigger-registry.md`

本 runbook 在 `TR-MACHINE-LANE-SWAP-MERGED=READY` 前仍具效力。Calendar W10 必須由既有 owner
完成產品 PR、state-only handoff與 hard-yield；不得因角色對調已準備就中途搬 worktree或改
writer。對調啟用後，本文件轉為 historical read-only，改用
`desktop-upstream-integration-lane-runbook.md`。

## 1. 筆電唯一責任

筆電是 `main` 整合者、Calendar／Travel／Conversation owner、中央文件與 schema owner。目標不是
同時寫所有功能，而是持續提供桌電可重現、可合併的穩定上游 checkpoint。

筆電獨占：

- `calendar/**`、`travel/**`、`project/**` 及其 tests。
- `intent/**`、LINE／公開回覆、`conversation-capabilities.txt`。
- 所有 Flyway migration 與 schema sequence，除非發出一次性 B3-Durable token。
- `pom.xml`、`application*.yml`／`application*.yaml`、共用 Spring wiring。
- `docs/decisions/current.md`、active index、雙機總體文件與本 runbook。
- PR 合併順序、branch protection 與最終完整回歸。

Checkpoint B 合併後，筆電不得再修改 `booking/**`；需要 Booking 變更時以 review comment 或
typed handoff requirement 交給桌電。

## 2. 目前工作順序

### L0：只完成文件 checkpoint D

- 將雙機總體 plan、兩份 runbook、桌電 prompt、Booking B3/B4 split 寫入 repo。
- 驗證 Markdown 路徑、active index 與 current decisions 一致。
- 不改 Java、migration、CI、tooling 或產品 branch。

### L1：建立共同 Git checkpoint A

- 先 fetch 並確認 `origin/main` 是否在文件化後有新提交。
- 盤點本機 `main` 相對遠端的已提交內容；不得用 reset、checkout、stash、clean 處理 dirty worktree。
- 透過正常 PR 將核准的穩定基線發布；禁止 direct push `main`。
- 回填實際 `origin/main` SHA。

### L2：發布 Booking B2 checkpoint B

- 保留現有 B2／V84 實作與 gate 證據，不在桌電重建。
- 先判斷 B2 與 Calendar W9-D／E dirty 內容能否安全分離；若不能，在筆電完成 W9-D／E 並一起
  穩定化，不做猜測式拆檔。
- 驗證 V84 仍是該 checkpoint 的實際 migration、RLS／application filter／crash recovery／
  reconciliation 證據仍成立。
- 使用 `scripts\mvn-safe.ps1` 跑 focused tests，出口再跑完整 `test`。
- 產品 PR 合併後 fetch 實際 SHA，再用 state-only handoff PR 把總體 plan 的 `desktopStart`
  更新為 `READY`、填入 `baseSha`，並將 `booking/**` 與 Booking active plan 的 gate evidence
  ownership 交給桌電。
- 同步把 `handoffs/laptop-trigger-state.json` 的 `TR-DESKTOP-B3-START` 更新為 `READY`，確認
  `origin/main` 可取得 published SHA，發出 receipt 後 `HARD_YIELD`。必須停下告訴使用者
  「桌電可啟動 `desktop/booking-b3-core`」；不得先進下一個 Calendar gate。

### L3：Calendar 與 Travel 主線

- 依 Calendar active plan 完成 W9-D、W9-E、W10；每輪重新取得 source／Flyway claim。
- W10 只解除 Travel 3B-B recurrence dependency；Travel 必須自行通過 3B-B。
- W10 合併後更新 `TR-CALENDAR-W10-MERGED` 並 `HARD_YIELD`，讓使用者決定先恢復 Travel、
  發 schema token（若其他條件也成立）或繼續筆電主線；不得默認替使用者選。
- 繼續 Travel 正式 typed view，Booking B3-Upstream Adapters 只有在 Travel Wheels 6–7 PASS
  handoff 後才能接入。
- 不因桌電等待而縮短 Calendar／Travel hard gate，也不讓桌電直接修改上游模組。

### L4：B3-Durable schema token

- 僅在 Calendar W10 完成、當下 schema lane 空閒、B3-Core/B4-Fake 已有綠色 handoff 時考慮。
- Fetch 最新 `main`，記錄實際 latest migration，分配一個 `GRANTED_ONCE` version。
- 限定一個 Booking-owned additive migration；若 `main` 先使用該版本，token 自動失效。
- Grant 或 stale/revoke 都更新 laptop state、發出 receipt 並 `HARD_YIELD`；不可只在 commentary
  提到版本而未形成 Git 證據。
- 桌電 PR 合併或放棄後立即把 token 記為 consumed／revoked，schema lane 回到筆電。

### L5：ADD LINE pilot

- 等桌電 `desktop/add-execution-core-v1` 合併後建立
  `laptop/add-execution-line-pilot-v1`。
- 沿用 `SUGGEST_NEXT_TASK`；無 category 才走 execution core，有 category 保留既有 task-only。
- 接 feature flag、owner-actor pilot、LINE reply 與 capability regression；flag 預設關閉。
- v1 維持 read-only，不新增 complete／snooze／can’t-do mutation。
- 合併後發出 `TR-ADD-LINE-MERGED` 並 `HARD_YIELD`；owner pilot 是否開啟仍由使用者決定。

## 3. Trigger producer 規則

- 每輪開始讀 `handoffs/laptop-trigger-state.json`；只由筆電修改這份檔案，不修改 desktop state。
- 跨 lane READY 只有在產品 PR 與後續 state-only handoff PR 都已合併，且 `origin/main` 可取得
  `publishedSha` 後成立。Local PASS、commit、push 或 draft PR 只能發
  `TR-PR-READY-FOR-REVIEW`，不能解鎖 consumer。
- `HARD_YIELD` 必須在 safe gate 更新文件、釋放 claims、通知使用者並結束當輪。使用者啟動桌電
  後，再明示恢復筆電 Calendar 工作。
- 不要求桌電 session 預先開著等待，也不假設另一個 session 會自動收到或重新 fetch。
- 若使用者明示睡覺／離線，只完成目前已授權 gate 到 safe exit；不得開始需要新決策的下一 gate。
- 發現人工切換過碎、等待總在使用者離線時發生、或任一 lane 長時間 idle，依
  `TR-HUMAN-AVAILABILITY-RISK` 主動提出改善建議，不自行變更 quiet hours、順序或權限。

## 4. 筆電禁止清單

- Checkpoint B 後直接修 `booking/**` 或桌電 branch 的實作。
- 在桌電仍持有 `GRANTED_ONCE` token 時新增另一個 migration。
- 把 Calendar W10 當成 Travel 3B-B 或 Wheels 6–7 自動 PASS。
- 為了讓 ADD 接線而改掉既有 category 查詢語意。
- 未經使用者確認變更 15 分鐘顯示準備窗、Now／Next 數量或 Calendar 優先規則。
- 合併未更新到最新 `main`、跨 lane、未通過 gate 或含不明檔案的 PR。

## 5. 每個 checkpoint 的回報

至少包含：

```json
{
  "lane": "laptop",
  "checkpoint": "D|A|B|S|ADD-LINE",
  "status": "PASS|BLOCKED",
  "baseSha": null,
  "publishedSha": null,
  "ownedPaths": [],
  "migration": {
    "observedLatest": null,
    "reserved": null,
    "handoffOwner": null
  },
  "tests": {
    "focused": null,
    "fullRegression": null
  },
  "desktopMayStart": false,
  "triggerEventId": null,
  "notification": "DURABLE_ONLY|NOTIFY_AND_CONTINUE|HARD_YIELD",
  "remainingWork": [],
  "risks": [],
  "userDecisionRequired": []
}
```

只有 commit 已可從 `origin/main` 取得，`desktopMayStart` 才能設為 `true`。

## 6. 驗證與資源規則

- Maven lifecycle 一律優先用 `scripts\mvn-safe.ps1`，不把 clean 當第一步。
- 同一台筆電一次一個 writable agent、Maven writer、Flyway writer 與重型 Docker gate。
- 不執行任何 `git diff`；用精準檔案讀取、Spotless、compile、tests 與 `git status --short` 驗證。
- 桌電執行 Maven 不與筆電共用 `target`，但 Git／schema 衝突仍以 branch ownership 與 handoff
  處理，不能因為是不同主機就忽略。
- 遇到產品語意、ownership、schema 或 handoff 歧義，直接問使用者。

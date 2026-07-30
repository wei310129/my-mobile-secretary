# Desktop Upstream／Integration Lane Runbook

> Assigned machine：Desktop
>
> 狀態：`PREPARED_NOT_ACTIVE`
>
> 啟用 trigger：`TR-MACHINE-LANE-SWAP-MERGED`
>
> 上位契約：`machine-lane-role-swap-plan.md`

## 1. 啟動前置

在所有條件成立前只能做一次 read-only preflight，不能輪詢、寫檔或取得資源：

1. fetch `origin/main`；
2. `handoffs/machine-lane-assignment.json` 為 `ACTIVE`，且 `effectiveFromSha` 可驗證；
3. `TR-MACHINE-LANE-SWAP-MERGED=READY` 的 published SHA 與 assignment 一致；
4. `handoffs/upstream-integration-trigger-state.json` 指派機器為 `DESKTOP`；
5. 舊 Calendar／Booking lane claims 已釋放，schema token 不在 active／granted 狀態；
6. 新 role-based worktree clean，沒有舊 worktree、patch、`target` 或 Docker volume 的搬移內容。

任一項不成立，狀態是 `BLOCKED`，不得自行修改 producer state 開閘。

## 2. Role ownership

啟用後 Desktop Upstream／Integration 負責：

- `calendar/**`、`travel/**`、`project/**` 與其 tests。
- Conversation／Intent／LINE／capability catalog。
- Flyway sequence 與 schema handoff。
- `pom.xml`、shared application config、中央 Spring wiring。
- 中央決策、active index、雙機計畫、registry 與 Upstream role state。
- Docker／Testcontainers、security-neighbor 與 root regression 的長時間 gate。

不得為了下游方便更改 Calendar／Travel 公開語意、放寬 RLS 或把 LLM 變成業務執行器。

## 3. 第一個候選 gate

Calendar W10 與 role swap 都 READY 後，Travel 3B-B 才成為可開始候選。因使用者已要求 3B-B
開始前一定確認，必須先取得使用者明確確認，再建立 `upstream/travel-3bb`。

Travel 3B-B：

- 是 recurrence／copy／split propagation 的獨立 gate；
- 不因 Calendar W10 PASS 而自動 PASS；
- 不自動開始 3C 或 Wheels 4–13；
- 完成後仍依 registry 執行產品 PR、state-only handoff 與
  `TR-TRAVEL-3BB-MERGED`。

沒有使用者確認時保持 parked，不持有 branch／claim／Maven／Docker。

## 4. Schema 與重型驗證

- 啟用後 Flyway owner 跟隨 Upstream／Integration role，不跟隨物理筆電名稱。
- B3-Durable token 必須從此 role 的 durable state 發出；token 仍是一次性、base/version/scope
  全部必填。
- Maven 走 `scripts\mvn-safe.ps1`，同時只允許一個 writer。
- Testcontainers／Docker gate 執行前確認桌電資源與其他 lane claim；不把 clean 當第一步。
- 完整回歸失敗未定位、migration 中途或 claim 未釋放時，不得 hard-yield 或啟動下一 gate。

## 5. State 與 handoff

此 role 只寫：

- `handoffs/upstream-integration-trigger-state.json`
- 中央 assignment／registry／decisions（在適用的獨立 coordination PR）

不得回寫歷史 `laptop-trigger-state.json` 或替 Commerce role ACK trigger。跨 lane READY 仍需
產品 PR與 state-only handoff 都進 `origin/main`。

## 6. 停止規則

遇到 ownership 衝突、缺 published SHA、schema token stale、dirty unknown owner、外部／破壞性
authority 或產品決策，立即停止。`HARD_YIELD` 時更新 durable state、釋放全部 claim、通知使用者
並結束當輪。


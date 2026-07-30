# Laptop Commerce／ADD Lane Runbook

> Assigned machine：Laptop
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
4. `handoffs/commerce-add-trigger-state.json` 指派機器為 `LAPTOP`；
5. 沒有其他 Laptop writable agent、Maven writer、Flyway writer 或重型 Docker gate；
6. 新 role-based worktree clean，沒有從舊筆電 root 搬入 dirty 檔案。

任一項不成立，回報 `BLOCKED` 後結束，不自行重建 Booking evidence。

## 2. Role ownership

啟用後 Laptop Commerce／ADD 負責：

- `booking/**` 與其 tests。
- `execution/**` 與其 tests。
- Commerce／ADD role state 與當輪產品 plan gate evidence。
- 對 Upstream typed contract 的最小需求與 review。

不得修改 Calendar、Travel、Project、Intent、LINE、capability catalog、中央 wiring／config、
中央 coordination docs 或 Flyway sequence。需要共用修改時提出最小需求給 Upstream role。

## 3. 第一個建議 gate

首選 `commerce/add-execution-core-v1`，原因是它無 schema、無外部 mutation、可用固定 `Clock`
與 focused tests 切成較小工作單元，較符合筆電熱穩定度限制。

開始前仍須：

- 驗證 B4-Fake durable evidence 已在 `origin/main`；
- 驗證 ADD Core 尚未被其他 branch／session執行；
- 取得當輪 source／Maven claim；
- 依既有 ADD v1 決策維持 1 Now、1 Next、Later count、15 分鐘 display-only prep window、
  owner pilot flag off 與 LINE read-only 邊界。

ADD Core 完成後發 `TR-ADD-CORE-MERGED`，不自行接 LINE／Intent。

## 4. B3-Durable 特別限制

B3-Durable 不因角色對調自動成為 Laptop 下一步。只有以下條件全部成立才可考慮：

1. 使用者另行明確確認；
2. Desktop Upstream role 發出 matching `TR-SCHEMA-B3-DURABLE-GRANT`；
3. token 的 base SHA、latest migration、reserved version 與 scope 可驗證；
4. 已安排可避免筆電過熱中斷的驗證窗口，或明確由桌電／CI 承接重型 RLS／Docker gate；
5. 仍遵守一個 additive Booking migration、不得修改既有 migration。

缺任一條件時保持 PENDING；不得自行跳 migration 版本或把 token 視為可轉讓。

## 5. 資源與熱風險控制

- 一次只執行一個 writable agent 與一個 Maven writer。
- 優先 focused tests；完整 gate 仍不可省略，但應安排在安全驗證窗口。
- 不保留 Docker／Testcontainers idle 資源，不在 background 輪詢。
- 觀察到熱當機、反覆重跑或人工切換成本時，發
  `TR-HUMAN-AVAILABILITY-RISK`；可建議調整 gate 或驗證機器，但不得自行改 ownership／順序。
- 不從 dirty root、舊 `target` 或另一台機器的 worktree 接續。

## 6. State 與停止規則

此 role 只寫 `handoffs/commerce-add-trigger-state.json` 與其產品 gate evidence；不得回寫歷史
`desktop-trigger-state.json` 或替 Upstream role ACK。

遇到缺 published SHA、typed contract、ownership 衝突、unknown dirty change、schema／外部／
破壞性 authority 或未定位測試失敗，釋放資源並停止。每個 `HARD_YIELD` 都必須先完成 durable
state 與使用者可見 receipt。


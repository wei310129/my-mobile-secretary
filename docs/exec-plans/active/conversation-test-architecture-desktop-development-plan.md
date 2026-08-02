# 全域對話測試架構重整與桌電執行計畫

> 狀態：`APPROVED_PENDING_MACHINE_LANE_SWAP`
>
> 使用者拍板：2026-07-31
>
> 未來 owner：桌電 Upstream／Integration lane
>
> Activation：只有 W11 產品 PR、state-only handoff、claims release 與
> `TR-MACHINE-LANE-SWAP-MERGED=READY` 均可由 `origin/main` 驗證，且筆電／桌電雙端 ACK 後生效。

## 1. 結果與問題定義

本計畫把對話測試的成功標準，從「語料很多、Intent enum 命中」改成「使用者要求經真實
application entry 正確執行、資料結果正確、沒有多做、回覆可理解且安全」。

目前已確認的主要漏洞：

- `conversation-capabilities.txt` 的契約測試只證明格式、編號與 marker 合法，不能證明能力可用。
- schedule live evaluation 只比對第一個 Intent type 與 command count；`REVIEW` 不會使一般測試失敗。
- 部分案例接受 `UNKNOWN`，但沒有同時證明零 mutation 與安全澄清。
- Intent 分類、handler、application service、持久化 final state 與公開回覆沒有一致的跨層 oracle。
- Calendar／Conversation Focus 的 sealed holdout 已有較完整標準，但尚未成為全專案共用方法。

完成後，任何「能力通過」聲明都必須指出證據等級、實際入口、mutation／state、隱私、重播與
未測路徑；案例數不能替代行為正確性。

## 2. 已拍板決策與不變量

1. 採全域架構重整，不只修單一提醒案例。
2. 採風險分批遷移：P0 destructive／external／privacy，接著 P1 一般 mutation／context，最後 P2 read-only。
3. 可執行規格放在能力旁的 Java typed scenario／fixture；catalog 只保留能力索引。
4. deterministic regression 是 PR／release hard gate；live model 採夜間＋發布前，不能取代 deterministic gate。
5. 先建立平台無關 Maven／JUnit gate，不在本計畫首批建立特定雲端 CI workflow。
6. `UNKNOWN` 只有在 oracle 同時要求零 mutation 與清楚澄清／不支援回覆時才合法。
7. 多個 accepted Intent 只有在 typed action、final state、mutation 與公開回覆契約完全等價時才允許。
8. LLM 只輸出結構化理解；時間、狀態、授權、地理、mutation、idempotency 與 destructive policy 由 Java 驗證。
9. 不用 exact phrase、巨大 regex、prompt-only 修補或測試捷徑換取案例通過。
10. 舊測試先保留；只有新證據已覆蓋相同風險後，才在獨立 cleanup gate 移除重複測試。

## 3. 證據分級與完成聲明

| 等級 | 名稱 | 能證明什麼 | 能否宣稱能力可用 |
| --- | --- | --- | --- |
| E0 | Catalog lint | 編號、格式、marker、引用合法 | 否 |
| E1 | NLU／Structured Output | schema、欄位、Intent interpretation | 否 |
| E2 | Application behavior | typed action、Java 規則、mutation、final state、reply facts | 是，限受控入口 |
| E3 | API／DB／RLS／LINE integration | 實際入口、transaction、persistence、actor/workspace、adapter | 是 |
| E4 | Live／latency／sealed holdout | configured model 穩定性、真實 channel、效能與未揭示案例 | 是，且不能替代 E2/E3 |

P0／P1 能力至少要有 E2 永久回歸；涉及 DB、RLS、API、LINE、quoted context 或 outbox 時必須補 E3。
發布聲明必須另外完成適用的 E4。E0／E1 的數量不得合併成「功能通過數」。

## 4. Test-only 介面與 scenario contract

在 test source 建立共用 conversation acceptance support；不先新增 production framework。最小型別：

- `ConversationScenario`：scenario ID、catalog IDs、risk、utterance／turns、entry path、latency class。
- `ScenarioSetup`：固定 `Clock`、actor/workspace/channel、前置資料、quote／pending context、privacy seeds。
- `ExpectedOutcome`：HTTP/result action、required／forbidden reply facts、expected state 與 exact delta。
- `ScenarioStateProbe<T>`：能力自有的 before／after snapshot；不得以全 DB dump 形成脆弱 oracle。
- `ConversationScenarioRunner`：執行 actual application／API／LINE entry 並套用共用 hard gates。

每個 mutation scenario 必須填 exact mutation delta；read-only、feedback、拒絕、模糊與 failure 必須填零異動。
共用 runner 統一檢查 replay、actor/workspace、public diagnostic leakage 與 reply state。領域 fixture 仍負責
自己的 domain invariants，避免建立不了解業務的萬用 assertion framework。

## 5. Scenario matrix 與 hard gates

每個普通能力建立 12–20 個有意義案例，依風險增加，不用同義句灌水。至少涵蓋：

- 真實問題、完整句、口語省略、同義／換序、標點／空格／常見錯字。
- 人、日期、地點、數量、週期與 channel 變化。
- 缺資料、歧義、read-only、feedback、拒絕、失敗與鄰近 Intent。
- 多輪 correction／confirmation／cancellation、explicit quote、expired／unauthorized quote。
- duplicate delivery、exactly-once mutation、actor/workspace/RLS 與月末／跨年／DST 等適用邊界。

案例分為 repair、permanent regression、sealed holdout。Builder 不得在 repair 階段看到 holdout oracle；
holdout 失敗要回到設計，該案例升為 permanent regression，並另補未揭示 holdout。

任一適用 hard gate 失敗，整個能力不算完成：typed action、Java 計算、mutation、final state、transaction、
idempotency、authorization、privacy、quoted-context priority、公開回覆與 latency／progress contract。

## 6. 首要黃金案例：修改既有行程提醒

前置狀態：明天下午兩點會議恰一筆；其提前 15 分鐘提醒恰一筆。

輸入：

> 明天下午兩點會議的提前提醒從十五分鐘改成三十分鐘，只修改既有提醒，不要再建立另一筆會議或另一個重複提醒

完整 oracle：

- typed behavior 是修改既有提醒；不能以 `ADD_SCHEDULE_REMINDER` 的新增語意代替。
- 會議仍恰一筆，欄位除明示範圍外不變。
- 提醒仍恰一筆，due time 改為會議開始前 30 分鐘，舊的 15 分鐘結果不再有效。
- 同一 request replay 後仍恰一筆且 state 不再改變。
- 無法唯一找到會議／提醒時零異動並澄清；不得猜目標。
- 回覆明確說明「已修改」或「需要補充」，不得宣稱另建完成。

若目前產品沒有安全更新能力，這是 P1 真實產品缺口；T0／T1 不順手修 production code，也不得把它標成
`UNKNOWN PASS` 或 `ADD PASS`。產品修復必須在 T3 另開小 gate。

## 7. 桌電 phase gates

### T0：共用基礎設施

- 更新 `docs/test-strategy.md`，登記 E0–E4、scenario contract、hard gates 與完成回報格式。
- 建立 test-only typed support 與 framework self-tests。
- 在 `pom.xml` 建立平台無關 JUnit tags／Maven profiles；PR 預設不依賴外部 model、LINE 或 credential。
- 不修改 production behavior、schema 或 migration。

出口：framework self-tests 全綠；既有測試仍可由標準 root command 執行；full regression 綠。

### T1：消除假陽性

- Catalog test 明確只產生 E0 lint 證據。
- 三組 live evaluation 標為 E4；opt-in 執行時需要機器可讀 summary 與明確 failure threshold。
- 建立 catalog row → executable scenario traceability；先完成 P0／P1 盤點。
- 產出 known-gap inventory，不在同一 PR 修產品。

出口：任何報告不再把 E0／E1 誤稱為可用能力；P0／P1 無法追溯時 hard fail 或明確列為 gap。

### T2：風險分批遷移

依序遷移：destructive／external／privacy → schedule／task／reminder mutation → context／quote／async → read-only。
相同業務不變量可共用 scenario，但每個 catalog ID 必須能追溯。每批都跑 neighboring capability 與 holdout。

出口：該批所有適用 E2／E3 hard gates 全綠，且公開回報列出尚未遷移範圍。

### T3：產品缺口修復

每個缺口獨立 gate／PR，先有 failing permanent regression，再做最小可泛化 production 修復。若涉及新 Intent，
同步 handler、capability catalog 與 regression；若涉及使用者可感知 domain event，同步 LifeRecord／tag graph。

出口：focused、neighbor、actual-entry、必要 RLS／persistence、live acceptance 與 root regression 全綠。

### T4：發布與清理

- 執行完整 deterministic、P0／P1 live、sealed holdout、關鍵 LINE E2E 與 latency gate。
- 只有證據等價時才移除重複舊測試；不得降低 assertion 或刪除困難案例。
- 更新本計畫 evidence，穩定後移至 `docs/exec-plans/completed/`。

## 8. 雙機 ownership 與允許路徑

本 ownership 只有 role swap activation 後生效。

| 範圍 | 桌電 Upstream／Integration | 筆電 Commerce／ADD |
| --- | --- | --- |
| `docs/test-strategy.md`、本計畫、active index | Writer | Read-only |
| `pom.xml`、共用 test profile／support | Writer | 提 typed request，不直接改 |
| `intent/**`、Intent／API／LINE conversation tests、capability catalog | Writer | Read-only |
| `calendar/**`、`travel/**` 與 integration ownership | 依 role-swap runbook | Read-only／consumer |
| `booking/**`、`execution/**` 與 tests | Read-only／review | Writer |
| Flyway migration | 依 role-swap schema token | 無 token不得修改 |

T0／T1 桌電不得以測試重整為由修改 `src/main/java/**`、migration、Booking／execution 或產品語意。
T2 若測試檔跨入 Booking／execution，由筆電提供 fixture／typed contract；桌電不得越權直接修改。

## 9. Resource concurrency matrix

| 資源 | 同時 writer | 取得方式 | 釋放點 |
| --- | ---: | --- | --- |
| Git branch／path ownership | 1 per path set | matching `origin/main` assignment＋clean worktree | PR merge／abandon receipt |
| Maven root `target` | 1 | `scripts/mvn-safe.ps1` 跨程序 mutex | Maven process 完成 |
| Testcontainers／Docker integration | 1 | lane claim 後 serial 執行 | container gate 完成並釋放 |
| Flyway schema | 1 | 一次性、base-bound schema token | migration PR merged／token revoked |
| Live model／LINE | 1 acceptance owner | 明確 environment／credential authority | terminal report 與外部資源釋放 |
| Central plan／state | assignment owner only | producer-owned state + Git ancestry | state-only handoff merged |

固定取得順序：`Git/base → path claim → Maven → Docker/Testcontainers → schema token → live/external authority`。
不得反向取得；拿不到後段資源時釋放不需要的前段 claim，不保持 session 空等。

## 10. Handoff、crash 與 stale-owner recovery

- 聊天通知不能取代 Git evidence；local file、local PASS、commit 或 push 都不能 activation consumer。
- Heartbeat／timeout 到期不能授權 takeover。必須重新 fetch、驗證 published SHA、producer state、claims release，
  並由 owner 或使用者留下明確 recovery receipt。
- Maven／Docker crash 後先確認沒有存活 writer；不得以 `clean`、刪 worktree、刪容器或改 migration 解除未知狀態。
- Scenario capture crash 不可算 PASS；sealed holdout capture／assert 維持兩次獨立 invocation 與 hash 驗證。
- PR 中途 crash 由同 owner 恢復；另一 lane 只能 read-only 診斷，不能接管 dirty branch。
- 每個 gate 合併後桌電發布 matching SHA、test summary、claims release 與下一 gate；通知等級為 `HARD_YIELD`。

## 11. Platform-neutral validation commands

現有基線：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -DskipTests test-compile
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
```

T0 新 profiles 的固定語意：

- fast：E0、E1 schema、E2 deterministic，無 Docker／external。
- integration：E3、Testcontainers、RLS、API／LINE adapter substitute。
- live：E4 configured model／latency，夜間執行，不阻擋一般 PR。
- release：完整 deterministic＋P0/P1 live＋sealed holdout＋關鍵 LINE E2E。

實際 profile 名稱與命令由 T0 同批落盤並加入 framework self-test；不得只寫文件卻沒有可執行 gate。

## 12. Machine-readable handoff contract

```json
{
  "planId": "conversation-test-architecture-v1",
  "status": "PENDING_ROLE_SWAP",
  "activationTrigger": "TR-MACHINE-LANE-SWAP-MERGED",
  "requiredActivationStatus": "READY",
  "producer": "laptop-integration",
  "consumer": "desktop-upstream-integration",
  "publishedSha": null,
  "firstGate": "T0",
  "externalMutationAuthority": "NONE",
  "schemaAuthority": "NONE_FOR_T0_T1",
  "desktopClaims": [
    "test-strategy",
    "conversation-test-support",
    "intent-api-line-conversation-tests",
    "capability-catalog-tests",
    "maven-test-profiles"
  ],
  "laptopExclusions": [
    "intent-conversation-tests",
    "capability-catalog",
    "pom-test-profiles",
    "central-test-strategy"
  ],
  "nextReceipt": "TR-CONVERSATION-TEST-T0-MERGED"
}
```

Activation 時由 role-swap owner 把 `publishedSha` 指向已存在於 `origin/main` 的實際 SHA，並把 status 更新為
`READY`。本文件存在、聊天 ACK 或使用者同意計畫，都不等於 activation 已完成。

## 13. 未測路徑與剩餘風險

- 尚未實作共用 runner、Maven profiles 或 P0/P1 coverage registry。
- 尚未量化 439 筆 catalog 中各風險級別與 E2/E3 缺口。
- 提醒修改目前可能缺少安全 update capability；不可在 T0／T1 偽報已修復。
- Live model 的 sample count／趨勢保存與 cost budget 尚待 T0 依現有 runtime 能力落盤，不得靜默降低產品 latency 門檻。
- W11 與 machine role swap 尚未 READY；桌電目前只能 ACK 本計畫並維持 safe exit。

## 14. 通知 receipt（2026-07-31）

| Consumer | Delivery | 必須後續行為 |
| --- | --- | --- |
| 筆電 Calendar W11 session | `DELIVERED`；完整核准版已成為該 thread 的新 turn | 只完成 W11；closure 時把本計畫與 index 納入中央 role-swap publish，不提前執行 T0 |
| 桌電 Booking／未來 Upstream session | `BLOCKED_CROSS_HOST_RELAY_UNAVAILABLE` | 維持 safe exit；由 role-swap owner 在 Git publish 後以 matching SHA 重送並取得 ACK |
| 雙機協調 session | `BLOCKED_CROSS_HOST_RELAY_UNAVAILABLE` | W11 closure 後登記 assignment、published SHA、雙端 ACK 與 activation receipt |

跨主機投遞失敗回報為 `No AppServerManager registered`／thread route failure。這不是產品 blocker，
但在桌電收到 matching Git state 與正式 receipt 前，`PENDING_ROLE_SWAP` 不得改為 `READY`。

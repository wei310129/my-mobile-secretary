# 筆電 × 桌電雙機平行開發總體計畫

> 狀態：`DOCUMENTED_NOT_STARTED`
>
> 文件日期：2026-07-26
>
> 目前桌電產品開發閘門：`BLOCKED_PENDING_PUBLISHED_HANDOFF`

## 1. 文件目的與優先序

本文件固定筆電與桌電的 ownership、Git／Flyway 邊界、合併順序與交接格式，讓兩台電腦可以增加
吞吐量，但不會同時修改同一個 bounded context、共用設定或 DB schema。

衝突時依序遵循：

1. 根 `AGENTS.md` 與較近目錄的 `AGENTS.md`。
2. `docs/decisions/current.md`。
3. 本文件。
4. `parallel-development-trigger-registry.md`。
5. `laptop-integration-lane-runbook.md` 或 `desktop-booking-add-lane-runbook.md`。
6. 各產品 active execution plan。

產品 active plan 仍是該 bounded context 的功能契約；本文件只決定「誰、何時、在哪一條 branch
實作」，不重寫 Calendar、Travel、Booking 或 Conversation 的產品語意。

## 2. 已拍板決策

- 桌電順序為 Booking B3／B4 優先，接著是 ADD execution。
- Booking 正式拆成 `B3-Core → B4-Fake → B3-Durable → B3-Upstream Adapters`。
- 第一波外部邊界只到 fake B4；不進 Duffel B5、不使用 sandbox／live provider、不使用
  Playwright，不取得 credential，不建立真實訂單或付款。
- Schema lane 在 Calendar Wheel 10 完成前由筆電獨占；桌電不得自行新增、修改、重新編號或複製
  Flyway migration。
- B2 發布 checkpoint 後，既有 `booking/**` 完整交給桌電；筆電停止修改，僅做整合 review。
- 每個 gate 一支 branch、一個 PR；禁止直接推送 `main`，PR 必須以最新 `main` 為基線並通過 CI。
- 同一台電腦同時只允許一個 writable Codex agent 與一個 Maven writer；跨電腦以 GitHub branch、
  path ownership 與本文件協調，不把單機 mutex 誤當成跨機器鎖。
- ADD v1 只使用時間、Task dependency 與 actor-adopted Calendar；不接位置、天氣或交通。
- ADD 第一畫面只回傳 1 個 Now、1 個 Next，Later 只顯示數量並可展開。
- 進入準備窗的固定 Calendar event 優先於逾期高優先 Task。
- 查不到 urgent item 時，推薦一個未阻塞的 open task，並明示「不緊急」。
- Calendar 沒有明示 reminder／buffer 時，只為本次顯示採開始前 15 分鐘準備窗；不建立通知。
- ADD v1 只做 owner-actor pilot、feature flag 預設關閉、LINE read-only query；不做完成、延後或
  「現在做不了」mutation。
- 沿用既有 `SUGGEST_NEXT_TASK`。無 category 查詢走新 execution core；有 `WORK` 等 category
  查詢維持既有 task-only 行為，不新增平行 Intent。
- B3-Durable 落地後，未被選取的 search job／candidate 保留 7 天後刪除；被
  authorization／execution 引用的 offer 依交易稽核生命週期保存。

## 3. 目前可驗證基線

以下是 2026-07-26 文件化當下的事實，不是桌電的啟動授權：

| 項目 | 狀態 |
| --- | --- |
| 筆電目前 branch | `codex/project-wheels-1-3a` |
| 筆電目前已提交 HEAD | `d696efb8c9561f8d62110af78243f854b6c330db` |
| 筆電本機 `main` | `77922ce4d6683fc0b1725a59bea4a507c2b5cd3c` |
| 當時 `origin/main` | `99882c47d66a0018e3cc6b259de4efbd015d9c53` |
| 筆電 dirty baseline | 134 筆；包含尚未發布的 Calendar、Booking B2／V84 與其他整合內容 |
| Booking 文件 gate | B2 PASS，full regression 1,435 tests／0 failure／0 error／18 skipped |
| GitHub 可取得的 B2 handoff | 尚未存在 |
| 桌電可否開始 B3-Core | 否；必須等待下節 Checkpoint B |

文件中的 PASS 證據不能取代 Git 可取得的 commit。桌電不得依 active plan 的描述自行重建
B2／V84，也不得以筆電工作樹的檔名或摘要猜測未提交內容。

## 4. Checkpoint 與啟動閘門

| Checkpoint | Owner | 內容 | 出口 |
| --- | --- | --- | --- |
| D：文件 checkpoint | 筆電 | 本文件、兩份 lane runbook、桌電 prompt、active index／current decisions 更新 | 文件連結與決策一致；尚不授權產品開發 |
| A：共同 Git 基線 | 筆電 | fetch 後確認遠端狀態，將已核准的本機穩定 `main` 內容透過 PR 發布 | `origin/main` 有唯一、可重現的共同基線 |
| B：Booking B2 handoff | 筆電 | Booking B0–B2、實際 V84、直接依賴與必要測試；若 W9-D／E 無法安全拆分，先完成並一併穩定化 | focused gate 與完整回歸綠；commit SHA、latest migration、dirty disposition 已回填；發出 `TR-DESKTOP-B3-START` |
| C：桌電啟動 | 桌電 | 從 B 的 `origin/main` SHA 建立 `desktop/booking-b3-core` | read-only preflight 全部通過才可寫檔 |
| S：Schema handoff | 筆電 | Calendar Wheel 10 完成後，針對 B3-Durable 發布一次性 schema token | 明示 owner、base SHA、當時 latest migration 與允許 scope |

在 Checkpoint B 尚未發布前，下列 machine-readable 狀態是唯一解讀：

```json
{
  "desktopStart": {
    "status": "BLOCKED_PENDING_PUBLISHED_HANDOFF",
    "requiredCheckpoint": "B",
    "baseSha": null,
    "bookingPhase": "B2",
    "actualFlywayLatest": "V84",
    "allowedAction": "READ_ONLY_PREFLIGHT",
    "forbiddenAction": "PRODUCT_OR_SCHEMA_MUTATION"
  }
}
```

筆電完成 Checkpoint B 的產品 PR 並合併後，必須依 trigger registry 建立 state-only handoff PR，
把 `status` 改為 `READY`、填入已存在於 `origin/main` 的實際 `baseSha`。State-only PR 合併且再次
驗證後，筆電發出 `HARD_YIELD` receipt、明確告訴使用者啟動桌電並結束當輪；不得先進下一個
Calendar gate。桌電不得自行修改這段狀態替自己開閘。

## 5. 路徑 ownership

| 路徑／責任 | Checkpoint B 前 | Checkpoint B 後 |
| --- | --- | --- |
| `calendar/**`、Calendar tests | 筆電 | 筆電 |
| `travel/**`、`project/**` 與其 tests | 筆電 | 筆電 |
| `booking/**` 與其 tests | 筆電完成 B2 發布 | 桌電獨占；筆電只 review |
| 新 `execution/**` 與其 tests | 不建立 | 桌電獨占 |
| `intent/**`、LINE／公開回覆、capability catalog | 筆電 | 筆電 |
| `src/main/resources/db/migration/**` | 筆電獨占 | 仍由筆電獨占，直到一次性 Schema handoff |
| `application*.yml`／`application*.yaml`、`pom.xml`、共用 Spring wiring | 筆電 | 筆電；桌電提出需求但不直接修改 |
| `docs/decisions/current.md`、active index、本文件、筆電 runbook | 筆電 | 筆電 |
| Booking active plan、桌電 runbook 的 gate evidence | 筆電發布 B2 前 | 桌電可精準更新；不得改中央決策 |
| 桌電啟動 prompt | 筆電 | 筆電；視為模板，不回填執行證據 |

「可讀取」不等於「可修改」。桌電可讀 Calendar／Travel 的公開型別與 handoff，但不得為了讓
Booking 或 execution 編譯而改上游模組；缺介面時回報筆電建立 typed handoff。

## 6. Git 與 PR 契約

1. 每個新 gate 都先 `fetch`，確認 `origin/main` 包含文件指定的 handoff SHA。
2. Branch 從該 SHA 建立，不從另一台電腦未發布的本機 branch、patch 或工作樹建立。
3. 固定 branch：
   - `desktop/booking-b3-core`
   - `desktop/booking-b4-fake`
   - `desktop/booking-b3-durable`
   - `desktop/booking-b3-upstream-adapters`
   - `desktop/add-execution-core-v1`
   - `laptop/add-execution-line-pilot-v1`
4. 一個 branch 只交付一個 gate；後一個 gate 等前一個 PR 合併，再從更新後 `origin/main` 建立。
5. PR 不得同時修改筆電與桌電 ownership 路徑，不得夾帶格式化、工具腳本或無關 cleanup。
6. 合併前 branch 必須包含最新 `main`、focused tests 綠、必要完整回歸綠，且由另一個 lane review。
7. 禁止 direct push to `main`。Branch protection／base-manifest CI 尚未落地前，以人工 review
   嚴格執行相同規則；不得因工具未完成而放寬 ownership。

## 7. Flyway 與 Schema handoff

跨機器不能依賴 `LOCALAPPDATA` mutex 或同一 `.git` common-dir 的 reservation。唯一 schema owner
預設是筆電，桌電只有在下列 token 已由筆電提交到 `main` 後才可新增一個 Booking-owned migration：

```json
{
  "schemaHandoff": {
    "status": "NOT_GRANTED",
    "ownerBranch": null,
    "baseSha": null,
    "observedLatestMigration": null,
    "reservedVersion": null,
    "allowedPath": "src/main/resources/db/migration/",
    "allowedScope": "booking search job, candidate binding, progress and terminal outbox only"
  }
}
```

實際授權時必須把 `status` 改為 `GRANTED_ONCE` 並填滿所有欄位。桌電仍需重新 fetch 並確認
`reservedVersion` 未被 `main` 使用；若不同，停止請筆電重發 token，不自行跳號。禁止修改任何
既有 migration。B3-Durable migration 不得新增旅客、付款、credential、raw provider payload，
也不得修改 Calendar／Travel／Task table。

## 8. 執行順序與避免桌電閒置

```text
Laptop:  D docs → A common base → B B2 handoff → Calendar W9-D/E → Calendar W10
Desktop:                                B3-Core → B4-Fake ─────────────┐
Laptop:  Travel typed handoff / schema token ─────────────────────────┤
Desktop:                  B3-Durable（token ready 時）或 ADD Core（等待時）
Desktop:                  B3-Upstream Adapters（Travel Wheels 6–7 PASS 後）
Laptop:                   ADD LINE pilot / feature flag / integration
```

若 B4-Fake 完成但 schema token 尚未 READY，桌電直接改做無 schema 的 ADD execution core；不得為
填滿負載而提早搶 Flyway、Intent、LINE、Calendar 或 Travel。

所有跨 lane 啟動、PR ready／merge、Calendar W10、Travel typed handoff、schema grant／stale、ADD
handoff 與人員可用時間提醒，依 `parallel-development-trigger-registry.md`。Producer 必須先把
產品 gate 與 state-only handoff 都合併到 `origin/main` 再通知；consumer 不預先開 session等待，
也不把聊天室訊息當成 Git 證據。

## 9. 必須直接詢問使用者的情況

任一 agent 遇到以下情況必須停下並直接問，不能自行合理化：

- 產品行為與本文件或 active plan 不一致，或需要更改已拍板提醒／buffer／排序語意。
- 需要跨越 ownership 路徑、修改同一個 DB table、共用設定、既有 migration 或中央文件。
- handoff SHA、schema token、latest migration、上游 typed contract 不存在或互相矛盾。
- 需要 Duffel／其他 provider、sandbox／live credential、付費服務、真實個資或外部 mutation。
- 需要 destructive migration、資料刪除、auto-cancel、盲目 retry 或未知交易狀態處置。
- 測試失敗無法定位、dirty worktree 含不明 owner 變更，或 branch 無法安全更新到最新 `main`。
- 已觀察到作息、睡眠／離線、人工 lane 切換或過碎 handoff 讓並行效率下降；agent 必須提出
  有證據的改善建議供使用者評估，不得自行猜 quiet hours 或靜默改流程。

已在本文件拍板的選項不重複詢問；只對新歧義、衝突或擴張範圍提問。

## 10. 文件完成不等於開發完成

本輪只建立執行契約。沒有實作 CI guard、沒有發布 Checkpoint A／B、沒有建立桌電產品 branch、
沒有改 Java／DB，也沒有執行 Maven。桌電 prompt 在 `desktopStart.status` 為
`BLOCKED_PENDING_PUBLISHED_HANDOFF` 時只允許 read-only preflight。

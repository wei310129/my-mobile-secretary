# Calendar W11-H 雙機併行開發與測試計畫

> 狀態：`APPROVED_ACTIVE_PREPARATION_BLOCKED_PENDING_COORDINATION_PUBLISH`
>
> 使用者拍板：2026-08-01
>
> Production owner：筆電 Upstream／Integration lane
>
> Evidence owner：桌電 route benchmark／sealed holdout lane
>
> 上位契約：Calendar Wheel 11、`two-machine-parallel-development-plan.md`、
> `parallel-development-trigger-registry.md`

## 1. 結論與目前閘門

W11 完成前只允許「筆電產品開發＋桌電獨立評測」。筆電維持 Calendar、Intent、LINE、Reminder、
Flyway、共用 wiring 與公開回覆的唯一 production writer；桌電只產生匿名化 provider benchmark、
sealed holdout 與黑箱公開回覆／latency evidence，不執行 Maven、Docker、Flyway 或 LINE monitoring。

桌電啟動採 fail closed：只有 `TR-DESKTOP-ROUTE-BENCHMARK-START` 已可由 `origin/main` 取得、
`publishedSha` ancestry 驗證成功，且桌電寫入自己的 ACK 後才可建立 evidence branch。Local PASS、
聊天訊息、本機 commit、push 或 draft PR 都不能取代這個 gate。Machine-readable laptop state 是
目前 gate 的唯一即時狀態；本文件的 observed snapshot 只供追溯。

```json
{
  "observedOn": "2026-08-07",
  "observedOriginMain": "efddcdac05983add51b5163bc64e5402579bc01a",
  "coordinationTrigger": "TR-DESKTOP-ROUTE-BENCHMARK-START",
  "coordinationStatus": "BLOCKED_NEEDS_PUBLISH",
  "externalMutationCount": 0
}
```

Calendar Wheels 0–10 與 matching W10 handoff 已發布。本文件不把 W11-H 核准冒充 H1–H7 PASS；
桌電 evidence lane 可在 coordination READY／ACK 後先行，筆電仍須逐 gate 完成 production 內容。

## 2. Ownership 與資源隔離

筆電唯一擁有：

- `src/main/java/**`、`src/main/resources/**`、`src/test/java/**` 的 W11 產品行為。
- Calendar／Intent／Conversation／LINE／Reminder／Planner／provider adapters。
- Flyway、`pom.xml`、共用設定／wiring、中央文件／state、產品 PR、LINE baseline 與 release receipt。
- 所有日期、時間角色、地理、可行性、提醒與 mutation 決策；LLM 只做 Structured Output 與表達。

桌電 branch 只允許：

- `docs/exec-plans/evidence/calendar-w11-h/desktop-route-benchmark/**`
- `docs/exec-plans/evidence/calendar-w11-h/sealed-holdout/**`
- `docs/exec-plans/evidence/calendar-w11-h/public-response-evaluation/**`
- `docs/exec-plans/evidence/development-environment/calendar-w11-desktop.json`

證據可包含匿名化輸入、machine-readable 結果、sealed hash／summary、公開回覆評分、latency／
failure／cost summary 與 provider freshness metadata。不得包含私人 LINE 原文、精確住址、API key、
token、UUID、workspace／actor／message identifier、raw credential 或可逆個資。

桌電禁止修改 `src/**`、`pom.xml`、`.mvn/**`、`scripts/**`、Flyway、中央文件、laptop state 或既有
production ownership 路徑；也不得執行 Maven、Docker、Flyway、LINE E2E／monitoring。Route benchmark
只允許 read-only query，所有 receipt 的 `externalMutationCount` 必須為 `0`。需要新 credential、付費
額度、未核准 provider scope 或真實個資時，觸發 `TR-EXTERNAL-AUTHORITY` 並停止。

Evidence lane 不取得 source、Maven target、Docker、DB、Flyway、LINE runtime 或 schema claim。桌電
ACK 後筆電可繼續 eligible production gate；兩邊只用 Git-retrievable evidence 交接。

## 3. 產品設計不變量

### 泛化 typed 語意

- 本人參加且有起訖時間：時間段行程。
- 送人、接人、交付等定點責任：Task／提醒；明確送與接保持兩個獨立責任。
- 陪同是否占用全程證據不足：只問一個必要問題。
- 不主動追問未被提出的「誰送誰接」。
- actor、beneficiary、responsibility、occupancy、time-role 使用 typed policy；人物、親屬、品牌或完整
  例句不得形成 domain branch。詞表／regex 只作 bounded lexical normalization。

### 秘書式回覆與時間

- 先回答當下重要事實，再問下一個必要問題；每次只問一題。
- 不輸出 Intent、validation、欄位名、handler、reason、schema、internal id 或 raw provider error。
- confirmation 前不宣稱完成；成功、失敗、未變更與處理中要明確。
- 使用注入 `Clock`。過時時間提出下一個合理日期並確認。
- 21:00 前裸「1 點」優先提出 13:00；21:00 後詢問凌晨或下午。
- `ARRIVE_BY`、`DEPART_AT`、活動起訖、route duration、buffer、reminder lead 不得互換。
- 抵達時間點不因缺少結束時間而失敗。

### Route、提醒與可行性

- 開車、機車、步行、地址及商家解析：Google。
- 台灣公共運輸：TDX 為主要候選，Google Transit 比較／備援；海外：Google。
- 直線估算不宣稱可行或準時；飛機只有使用者明確提出才納入。
- 不以平均分數強迫唯一 provider；場景優勢不同時保留 typed routing policy。
- Google driving arrive-by 由 Java 迭代候選出發時間、ETA 與 buffer，逼近最晚安全出發時間；沒有
  第二條可靠路線時不虛構替代方案。
- route duration ≤30 分鐘：出發前 10 分鐘、出發時；31–90 分鐘：30 分鐘、出發時；>90 分鐘：
  60 分鐘、20 分鐘、出發時。
- 出發時間改變時相對提醒同步移動；只有「以後都這樣」才保存永久偏好。
- H7 檢查前一行程到本次起點及本次終點到下一行程；不可達先提首選調整。使用者確認風險後仍可
  保存；同 risk revision 只提醒一次，ETA 惡化或建議提前至少 10 分鐘才再通知。

## 4. H1–H7 gates

| Gate | 筆電交付 | 桌電 evidence | 出口 |
| --- | --- | --- | --- |
| H1 泛化語意 | typed semantic-role policy | 人物／地點／句型替換 holdout | 零專用 branch；ambiguity 零 mutation |
| H2 秘書式回覆 | diagnostics／public response 分離 | 自然度、直接回答、completion truth、leak | 每案例 UX 五維 ≥4/5；零內部洩漏 |
| H3 時間理解 | `Clock`、過時時間、裸時間、time-role | day／21:00／DST／timezone boundary | Java deterministic；不猜值 |
| H4 多輪草稿 | typed draft、quote 優先、RLS、replay | correction／confirmation／parallel drafts | exact mutation；replay once；cross-actor fail closed |
| H5 路線規劃 | provider-neutral interface、Google／TDX、Calendar 接線 | 真實場景 benchmark | freshness／ETA／班次／步行／latency／failure／cost |
| H6 提醒 mutation | Calendar V2 graph、relative reminder、atomic mutation | 公開摘要與 unexpected mutation | exact intended、zero unintended、relative move |
| H7 可行性 | adjacent itinerary、risk confirmation、降噪 | 未揭示跨區黑箱案例 | H1–H6 全綠；risk revision debounce |

每個 gate 分 repair、permanent regression、sealed holdout 三批。Holdout expected answers 在 repair／
regression 通過前不得揭露給 evaluator。矩陣涵蓋完整與口語省略、同義／重排／錯字、不同人物／
日期／地點、missing／ambiguous、neighbor intent、quoted context、duplicate delivery、actor/workspace／
RLS、failure/read-only 零 mutation 與 latency。

## 5. Provider benchmark contract

至少覆蓋都會公車＋捷運、台鐵／高鐵跨城、偏鄉與跨縣市公車、轉乘／末班車／營運異動，以及
Google driving／motorcycle／walking、台灣 transit 與一組海外 route。

每個匿名 case 記錄 provider、mode、query time-role、timezone、request time bucket、結果狀態、ETA、
scheduled／real-time distinction、班次、步行段、轉乘、freshness、latency、failure class、warm/cold／
cache state 與可得成本單位。禁止只保存自然語句而缺少可比較欄位。

```json
{
  "schemaVersion": 1,
  "suiteId": "calendar-w11-h-route-benchmark",
  "baseSha": null,
  "caseSetHash": null,
  "providers": [],
  "scenarioClasses": [],
  "sampleCount": 0,
  "successRate": null,
  "latency": {"medianMs": null, "p95Ms": null, "slowestMs": null},
  "freshness": null,
  "costSummary": null,
  "externalMutationCount": 0,
  "containsSecretsOrPersonalData": false,
  "conclusion": "SCENARIO_ROUTING|SINGLE_PROVIDER_SUPPORTED|INSUFFICIENT_EVIDENCE"
}
```

桌電不得自行改 routing policy。筆電只在 evidence PR 合併並驗證 SHA、schema、sample、privacy 與
zero-mutation receipt 後，才把結論轉成 Java typed policy；不直接複製未提交資料。

## 6. Coordination-only READY／ACK

```json
{
  "eventId": "TR-DESKTOP-ROUTE-BENCHMARK-START",
  "notification": "NOTIFY_AND_CONTINUE",
  "producerLane": "laptop",
  "status": "READY",
  "publishedSha": null,
  "baseSha": null,
  "allowedPaths": ["docs/exec-plans/evidence/calendar-w11-h/desktop-route-benchmark/**", "docs/exec-plans/evidence/calendar-w11-h/sealed-holdout/**", "docs/exec-plans/evidence/calendar-w11-h/public-response-evaluation/**", "docs/exec-plans/evidence/development-environment/calendar-w11-desktop.json"],
  "forbiddenPaths": ["src/**", "pom.xml", "scripts/**", "src/main/resources/db/migration/**"],
  "resourceClaims": [],
  "externalMutationCountRequired": 0,
  "mustYieldNow": false
}
```

這是無 production/resource claim evidence lane 的窄例外：仍需 Git READY 與 consumer ACK，但不移交
ownership，因此 producer 可通知後繼續。scope 一旦擴張到 source、Maven、Docker、schema、LINE
runtime、credential、付費額度或 external mutation，例外失效並改走 `HARD_YIELD`。

桌電 fetch 後驗證 SHA ancestry、allowlist／denylist 與 clean worktree，只在自己的 state 記錄
`ACKNOWLEDGED`，再建立 `desktop/calendar-w11-h-route-benchmark`；不得修改 laptop state 自行開閘。

## 7. 合併與角色切換順序

1. 筆電發布本計畫、registry、laptop state 與單一 request manifest 的 coordination-only PR。
2. Fetch 實際 merge SHA，再以 state-only handoff 標 READY；桌電 fetch／ACK。
3. 筆電完成 H1–H4；桌電並行提交 provider benchmark、sealed holdout、public-response evidence PR。
4. 筆電驗證 evidence SHA 後實作 H5–H6；H1–H6 全綠後才進 H7。
5. 產品修正完成後刷新 runtime，建立全新的 24 小時且至少 20 個真實 LINE inbound baseline；舊
   baseline 不得沿用，私人原文不得提交。
6. focused、neighbor、RLS、LINE、root、monitoring 全綠後建立 W11 產品 PR。
7. 產品 PR 合併後建立 matching state-only handoff，驗證 `origin/main` 並釋放 source／Git／Maven／
   Docker／Flyway／LINE monitoring claims。
8. 更新中央文件與全域對話測試架構計畫，發布 W11 `HARD_YIELD`。
9. 另行完成 `TR-MACHINE-LANE-SWAP-MERGED=READY` 與雙端 ACK；只有此後桌電成為後續
   Upstream／Integration production owner。

## 8. Release hard gates

- 零人物／品牌／例句專用 branch；至少一個未見 holdout 與兩個 neighbor counterexample 通過。
- exact mutation、zero unintended mutation、transactionality、idempotent replay。
- actor/workspace application filter、RLS、explicit quote 優先且不可跨 boundary。
- success／failure／provider-error seed 均零 internal diagnostic／identifier leak。
- Low terminal P95 ≤1.5s；medium P95 ≤4s 且超過 2s 有 durable progress；high/multi-provider 在 1–2s
  有 durable progress，另記 sample、median、P95、slowest、environment、mode 與 warm/cold。
- Google／TDX 真實 benchmark 有場景別 freshness、ETA／班次／步行、latency、failure rate 與 cost。
- 相對提醒移動、永久偏好明示、adjacent-itinerary risk 與 revision debounce 正確。
- focused、neighbor、RLS、API、quoted context、LINE E2E、root regression、全新 24h／20 inbound
  monitoring 全綠，所有 claims 有 `RELEASED` receipt。
- 依 `docs/agent-context/development-environment-preflight.md` 分別產生 laptop 與 desktop 的
  `calendar-w11` Automatic environment review evidence。Laptop 至少驗證 `READ_ONLY,MAVEN,DOCKER_TEST`；
  desktop evidence-only lane 至少驗證 `READ_ONLY`。兩份都必須零 open blocker、contract fingerprint
  相符、未逾期、已進 Git，並以 matching `-MachineAlias`／`-RequiredCapability` 驗證；任一缺少或 stale
  時 `assert-dev-environment-review.ps1` 必須 fail，W11 不得標 `PASS`／`MERGED`。

任何 hard gate 失敗都未完成；未跑 live provider、LINE、24h 或外部環境路徑必須明列，不得以
substitute 冒充。

## 9. Stable gate 與剩餘風險

- deterministic、provider benchmark、actual-entry 分批；不為每個小修重跑完整 root suite。
- 只有 ownership 單一、測試綠且 evidence 完整的 stable gate 才提交；混合 ownership 不標 PASS。
- Context 壓縮候選：coordination READY/ACK、H4 PASS、evidence merged、H6 PASS、H7 PASS、W11
  monitoring closure；實際時機仍由 agent 依決策與驗證狀態判斷。
- Wheels 0–10 與 W10 durable handoff 已發布；W11 H1–H7、provider benchmark、LINE/24h monitoring
  與 release closure 均尚未以本計畫驗證。
- 目前 coordination contract 尚未進 `origin/main`，桌電不能開始。
- Credential、quota、計價與資料授權需桌電 preflight；secret 不進 repo。
- 證據不足時結論必須是 `INSUFFICIENT_EVIDENCE`。

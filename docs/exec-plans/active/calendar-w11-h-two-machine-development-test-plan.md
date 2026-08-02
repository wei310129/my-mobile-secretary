# Calendar W11-H 雙機併行開發與測試計畫

> 狀態：`APPROVED_ACTIVE_PRODUCT_WORK_BASELINE_INVALIDATED_COORDINATION_NOT_PUBLISHED`
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
聊天訊息、本機 commit、push 或 draft PR 都不能取代這個 gate。

```json
{
  "observedOn": "2026-08-02",
  "laptopBranch": "codex/calendar-w11-cutover",
  "laptopHead": "e5b2250ce922a0f489f885caa03453554d7491b3",
  "observedOriginMain": "132308e42d8a4c5dcd4929372a885fe1db66fd8a",
  "coordinationTrigger": "TR-DESKTOP-ROUTE-BENCHMARK-START",
  "coordinationStatus": "BLOCKED_NEEDS_PUBLISH",
  "externalMutationCount": 0
}
```

W9-E 與 Wheel 10 已依 Calendar 主計畫合併並發布 matching handoff；W11-A～W11-G 的 cutover、語意修復、
OpenAI Luna read-only route、signed LINE、focused／neighbor／RLS／root gate 亦已有可追溯證據。這些既有
PASS 不自動等於本文件 H1～H7 全部完成；桌電 evidence lane 仍只能在 coordination READY／ACK 後先行，
筆電則持續擁有所有 production gap、整合與 release 判定。

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
  "status": "BLOCKED_NEEDS_PUBLISH",
  "publishedSha": null,
  "baseSha": null,
  "allowedPaths": ["docs/exec-plans/evidence/calendar-w11-h/desktop-route-benchmark/**", "docs/exec-plans/evidence/calendar-w11-h/sealed-holdout/**", "docs/exec-plans/evidence/calendar-w11-h/public-response-evaluation/**"],
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

1. 筆電發布本計畫、registry、laptop state 的 coordination-only PR；在 matching merge 前上述 JSON
   維持 `BLOCKED_NEEDS_PUBLISH`。
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

任何 hard gate 失敗都未完成；未跑 live provider、LINE、24h 或外部環境路徑必須明列，不得以
substitute 冒充。

## 9. Stable gate 與剩餘風險

- deterministic、provider benchmark、actual-entry 分批；不為每個小修重跑完整 root suite。
- 只有 ownership 單一、測試綠且 evidence 完整的 stable gate 才提交；混合 ownership 不標 PASS。
- Context 壓縮候選：coordination READY/ACK、H4 PASS、evidence merged、H6 PASS、H7 PASS、W11
  monitoring closure；實際時機仍由 agent 依決策與驗證狀態判斷。
- 目前 coordination 尚未進 `origin/main`，桌電不能開始。
- Credential、quota、計價與資料授權需桌電 preflight；secret 不進 repo。
- 證據不足時結論必須是 `INSUFFICIENT_EVIDENCE`。
- W9-E 與 Wheel 10 已合併；matching `TR-CALENDAR-W10-MERGED=READY` 可由 `origin/main` 驗證。W11-G
  最新完整 root 為 1,765 tests／0 failure／0 error／21 opt-in skipped，Spotless PASS，官方 runtime 與
  LINE external probe 亦為 PASS。這些證據只涵蓋 Calendar W11 主計畫已列 gate，不得冒充尚未執行的
  W11-H provider benchmark、sealed holdout 或本計畫新增 gap。
- 第四次 24h baseline 曾於 `2026-08-02T06:06:38.335359+08:00` 開始，但已在
  `2026-08-02T06:27:23.2638731+08:00` 因核准的 W11-H production work 正式失效；該窗口不得用於
  release。產品修正完成且 runtime refresh 後，必須另建全新的 24 小時／至少 20 筆真實 LINE 文字
  inbound baseline。
- 目前 coordination 尚未進 `origin/main`，桌電不能開始；因此 Google／TDX benchmark、sealed holdout
  與 public-response 黑箱評測仍為未完成 release evidence。

## 10. 2026-08-02 implementation ledger

本段只記錄 laptop worktree 的 local product evidence，不將 local PASS 冒充 coordination、W11 或
role-swap READY。

- H1：原本以「上課／放學／學校」目的地詞表決定接送 domain 行為的 policy，已改為 typed
  actor／beneficiary／responsibility／occupancy decision。本人參與且有區間保留 Schedule；送人、接人、
  文件／包裹交付轉定點 Task；本人被載送保留全區間；陪同占用不明時只問一題；沒有交通 evidence
  不主動問誰送誰接。人物、親屬、品牌、課程或目的地名稱不形成 domain branch，regex 只保留動作
  grammar、時間 qualifier 與非乘客交付排除。19-case typed matrix 加 transport conversation neighbor
  batch 共 63 tests PASS。
- H2：`UserReplySafetyPolicy` final guard 已擴充攔截 Intent／validation／handler／schema、內部 reason、
  時間／reference 欄位名、Calendar node／plan／workspace／actor identifier 與 UUID；命中時回到一個
  secretary-style 問題，並明示沒有建立或修改資料。公開回覆、formatter、provider-neutral intent 與
  route-risk neighbor batch 53 tests PASS。
- H3：所有新增政策使用注入 `Clock`。過時 mutation 不執行，提出下一個相同本地時間並確認；21:00
  前裸「1 點」先提 13:00，21:00 起詢問 01:00／13:00；明示凌晨／下午、11 點、週一點名與「差一點」
  不誤攔。Calendar timed-point 不因缺 end 失敗；route request 的 ARRIVE_BY／DEPART_AT、route duration、
  safety buffer 與 reminder lead 維持 typed 分離。H3 focused batch 70 tests PASS。
- H4：V95 新增 actor-owned、conversation-scope-bound `calendar_intent_draft`，保存 typed placement、
  location、category、request／confirmation hash、status、revision、expiry 與 materialized Calendar plan
  identity，並啟用／強制 RLS。提案與不同 inbound 可平行存在；同 inbound exact replay；修正、確認與
  放棄都使用 expected revision；確認只 materialize 一個 Calendar graph，放棄保持 Calendar mutation=0。
  Conversation focus 以 scoped workflow UUID 選定 draft，initial activity revision 在 ENTER／SWITCH 同一
  transition 原子保存；trusted quote 恢復的 active focus 優先，不以「最新一筆」猜測。公開回覆只說
  尚未建立、下一個可回答問題或完成事實，不輸出 revision、欄位或 internal id。
- H4 focused／migration／NOBYPASSRLS／handler／actual-entry／capability／public-reply batch 43 tests PASS；
  H1 transport grammar 與既有 conversation focus atomicity／actual-entry 鄰接 batch 75 tests PASS。
  NOBYPASSRLS runtime role 已實測 owner 只見本人 draft、同 workspace peer 只見本人 draft、system 為零，
  peer 對 owner update 為 0。兩個 actual-entry 案已覆蓋同 request replay、確認前零 Calendar mutation、
  parallel drafts、trusted suspended-focus resume、只修正被引用 draft、改名後確認與 single materialization。
  同時修正「直接建立」被接人 grammar 誤判的 false positive，非交通複合詞 holdout 已加入。
- H5：新增 provider-neutral request/result、typed transport-mode policy、Google Routes adapter、TDX
  Taiwan transit first／Google fallback 與非 transit arrive-by Java iteration；直線估算只保留為
  `APPROXIMATION`，不得宣稱可行。focused wiring batch 51 tests PASS。
- H6：新增 Java adaptive departure reminder policy，邊界為 ≤30、31–90、>90 分鐘三段；同一交易建立
  全部相對提醒，node revision 時一起移動，永久偏好不因單次建議寫入。focused batch 18 tests PASS。
- H7：相鄰行程改用 plan/node/source-owner typed identity；V94 建立 actor-owned
  `calendar_route_risk`，兩端均為真實 Calendar node FK，並啟用／強制 RLS。首次風險、來源 node
  revision、risk kind、ETA 惡化、建議出發提前至少 10 分鐘或 resolved recurrence 才開新通知 revision；
  exact replay 與已確認同版保持安靜，確認支援 expected-revision replay，恢復可行會 durable resolve。
- H7 公開查詢已接入既有 `CHECK_FEASIBILITY`：未指定兩個 legacy title 時檢查 Calendar personal
  projection 的所有相鄰定點行程；無相鄰行程、證據不足、可行、可調整與 locked risk 都由 Java
  secretary-style policy 回覆，每次最多問一題，不輸出 Intent、欄位、reason、schema 或 internal id。
- H7 deterministic／migration／RLS 核心批次 20 tests PASS；能力目錄與公開回覆鄰近批次 14 tests
  PASS。NOBYPASSRLS runtime role 已實測 owner 可見，同 workspace 其他 actor、其他 workspace 與 system
  scope 不可見，越權 update 為 0。
- H7 新行程 actual-entry 已接上 ordinary `CREATE_SCHEDULE`：V96 在 actor-owned draft 保存
  `ROUTE_RISK`／`INSUFFICIENT_EVIDENCE` fingerprint；第一次遇到新風險 revision 只提示且 Calendar
  mutation=0，同 fingerprint 的明確再次確認才 materialize。草稿修正會原子清除舊 fingerprint，下一次
  依新版重算；Calendar personal projection 同時納入本人直接擁有的 plan node 與 adopted constraint，
  但忽略候選草稿內部 start→end，不把同一行程誤判成跨行程移動。建立 interval 時保存 start／end
  兩個 location node，風險確認後建立一個 plan、一個 durable `CONFIRMED` risk，request replay 不重複。
- V97 將 recurrence rule／until 納入同一 typed draft；共用 Java grounded recurrence policy 供既有
  Calendar V2 與 draft lane 使用。固定行程不再繞過 preflight，materialize 在同一交易建立唯一 Calendar
  plan 與 recurrence series；無法由使用者原話落實的週期在零 mutation 下回問。
- H7 actual-entry 4 tests PASS，涵蓋 risk double-confirm、無相鄰風險立即建立、修正使舊核准失效、
  insufficient evidence 不宣稱可行；H4/H7 actual-entry 鄰接 6 tests PASS。H4/H7 focused、RLS、migration、
  handler 與 route-risk 鄰接 42 tests PASS；recurrence policy／V97／single-series 整合批次 28 tests PASS。
- Root regression 首輪 1,890 tests 找到 5 個 failure，已修正過期 fixture、title/source 跨邊界語法誤判、
  HOUSEHOLD shared-plan personal-route 旁路、focus overload mock 與低階 SQL review 登錄。精準重跑 29 tests
  PASS、受影響完整鄰接 97 tests PASS；修正後全新 root regression 1,891 tests／0 failure／0 error／21 個
  opt-in skipped PASS，耗時 934.4 秒。
- Runtime refresh 已以 Java 21 執行官方 `dev-start.ps1 -SkipDocker -SkipDispatcher` 並 exit 0；隨後
  `dev-status.ps1 -ExternalLineProbe` exit 0，確認 main `UP`、PostgreSQL／Redis healthy、LINE connected，
  Dispatcher optional-unavailable 且維持 failure-isolated。service generation 為
  `8d350628a8c34dcda55d580403807827`，runtime startedAt 為
  `2026-08-02T11:35:17.5842273+08:00`。第一次不帶 `-SkipDocker` 因 worktree Compose 與健康 shared
  container 固定名稱衝突而 fail closed；另一次背景 Maven 未繼承有效 `JAVA_HOME`，均未形成可用 runtime，
  沒有刪除、重建或清空既有資料容器。
- 第五次 monitoring baseline 狀態為 `IN_PROGRESS`。於
  `2026-08-02T11:40:13.553251+08:00` 以 PostgreSQL `REPEATABLE READ READ ONLY` transaction 建立，只讀
  metadata count，不讀 LINE 原文、payload、外部 message ID、UUID 或 secret，也沒有 mutation。startedAt
  為 `2026-08-02T11:35:17.5842273+08:00`，notBefore 為
  `2026-08-03T11:35:17.5842273+08:00`；起始 LINE inbound／text／decision trace 均為 32，trace outcome 為
  CLARIFICATION 11、FALLBACK 2、SUCCEEDED 19，legacy `schedule_item` 4、`calendar_plan` 0、`intent_issue` 22、
  Flyway V97。出口要求 LINE inbound/text 至少各 52、其中至少 20 筆為 startedAt 後的真實本人文字 turn，
  legacy `schedule_item` delta=0，並完成新 trace、Calendar V2 route、scenario、privacy 與 latency 稽核；
  synthetic probe、focused test 與 provider benchmark 不得計入。
- 尚未完成：coordination commit／push／merge（GPG pinentry timeout，且本機 `gh` 未登入）、桌電 ACK、
  Google／TDX 真實 benchmark、sealed holdout，以及第五次 baseline 的 24h／20 inbound closure audit。
  因此 H1–H7 與 W11 均不得標 release PASS。

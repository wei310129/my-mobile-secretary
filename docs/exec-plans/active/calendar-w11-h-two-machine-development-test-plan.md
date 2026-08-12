# Calendar W11-H 雙機併行開發與測試計畫

> 狀態：`APPROVED_ROUTE_LIFECYCLE_PRODUCTION_REPAIR`
>
> 使用者拍板：2026-08-01
>
> Production owner：筆電 Upstream／Integration lane
>
> 2026-08-09：route-origin／context、route reply／buffer／Maps、safe-time reschedule與全服務
> context-transition production hardening已完成focused、fresh clean root、runtime、官方LINE及Laptop Automatic review。
> PR #41桌電provider benchmark結論為`SCENARIO_ROUTING`，sealed holdout／public-response evaluation與Desktop
> Automatic review均曾PASS；真人LINE後續揭露route context lifecycle缺口，第十七次baseline已永久失效。
> 本輪只實作route contributor，其他能力依獨立rollout計畫逐批審查，不一次套用。
>
> Evidence owner：桌電 route benchmark／sealed holdout lane
>
> 上位契約：Calendar Wheel 11、`two-machine-parallel-development-plan.md`、

## 2026-08-10 route interval／conditional buffer／start reminder gate

- route卡片與Calendar plan只保存provider depart→arrive；一般緩衝、停車、等車不得擴張核心區間。沒有與既有
  項目過近且任一側位移的條件時，不問、不設一般緩衝。外部活動本身仍不繼承route operation defaults。
- start reminder採route-only上層lifecycle：新建後問一次、relative調整自動跟隨、fixed調整重新確認並
  exactly-once取消舊review-required規則。其他能力只列入分批rollout，不在本輪production啟用。
- focused 16/16、context/home/explicit-time 10/10、standalone actual-entry 17/17、fixed-review integration 1/1
  PASS；20-sample warm P95=494 ms，既有≤1,000 ms hard gate未放寬。第十八次baseline仍永久失效。
- conditional buffer續作使用typed keep/adjust pending分支；failure-first 2/2、常用語法5/5、
  conversation/lifecycle/inventory 25/25、standalone 17/17與Calendar draft actual-entry 20/20已驗證。
- safety-neighbor 42/42、REST／LINE／reminder 38/38、migration／FORCE RLS 2/2、fresh clean root
  2,176 tests（18 skipped）全綠，runner execution 555.5秒。runtime generation
  `71fa229818634462877fd313e5c06b6b`健康且官方LINE connected；Automatic reviewedAt
  `2026-08-10T10:20:06.5040303Z`、六項MATCH、PASS/openCount 0，獨立assertion PASS。
- 第十九次24h／20筆baseline尚未啟動；先等待本版真人LINE route lifecycle代表案例，不以ExternalLineProbe
  冒充語意驗證。
- 真人LINE代表案例揭露direct-overlap問題缺少主詞／原因，且一般connection risk缺少可用／所需／不足分鐘。
  production已改為三種typed conflict question與量化原因；上述runtime／Automatic因production變更失效。
  official managed stop目前把指令自身operation判成unclassified exact-resource owner，focused與root gate維持
  BLOCKED，未taskkill、未繞過ownership。
- 2026-08-12 真人LINE在route place child直接提供`maps.app.goo.gl`分享連結時，intent與child routing皆正確，
  但Maps resolver只展開短網址主機的第一跳；Google中繼網址仍需第二次allowed-host redirect時即過早停止，
  因無座標而typed unresolved。修正後所有allowlisted HTTPS Google Maps hosts均可在既有四跳上限內繼續展開，
  仍拒絕user-info、port、非allowlisted host與redirect loop；失敗時child由OFFER一致轉為DETAILS，parent保留且
  Place／Calendar mutation皆為0。Failure-first 1/1先紅；Google client 9/9、PlaceService 12/12、route Maps
  actual-entry 4/4 PASS，runner execution分別30.5、12.1、40.0秒。未獨立重查或記錄真人原始URL；fresh runtime
  generation `1fb94d88ed2b4aa5a37bbe6706194e5d`已啟動，官方LINE connected。第十九次baseline仍未啟動，
  本次runtime與focused evidence不得冒充PR階段clean-root／Automatic／baseline gate。
- 同日第一次修正後真人重測仍typed unresolved。以fresh Google-scoped READ_ONLY receipt對同一匿名化連結做
  shape-only audit，確認實際鏈為`maps.app.goo.gl`→`maps.google.com`→`maps.google.com`→`www.google.com`
  （302／302／302／200）；最終URL是`q + ftid`，不是`/maps/place/.../@coordinates`。`q`為23字Unicode
  地點文字、無座標、非`loc:`／`place_id:`且在既有300字bound內；resolver原先完全未讀text query，故
  Places key即使usable也不會被呼叫。第二次production修正以同一bounded allowlisted URI resolver抽取`q`／
  `query`文字，無座標時交給既有Google Places typed search；不信任或保存`ftid`。Failure-first 1/1先紅，
  修正後Google client 10/10、PlaceService＋route Maps actual-entry 15/15 PASS，runner execution 41.8／46.2秒；
  external Google query 3次、mutation 0，真人URL／地名／座標未寫入文件或測試。fresh runtime已更新且官方
  LINE connected；仍須真人重傳同一連結驗證，focused不得冒充完整release gate。
> `parallel-development-trigger-registry.md`

## 1. 結論與目前閘門

W11 完成前只允許「筆電產品開發＋桌電獨立評測」。筆電維持 Calendar、Intent、LINE、Reminder、
Flyway、共用 wiring 與公開回覆的唯一 production writer；桌電只產生匿名化 provider benchmark、
sealed holdout 與黑箱公開回覆／latency evidence，不執行 Maven、Docker、Flyway 或 LINE monitoring。

桌電 coordination 已完成：`TR-DESKTOP-ROUTE-BENCHMARK-START`、ACK、evidence policy與benchmark evidence
均已進`origin/main`。這只解除桌電evidence lane啟動條件，不代表benchmark或release PASS。Local PASS、
聊天訊息、本機 commit、push 或 draft PR 都不能取代release gate。

```json
{
  "observedOn": "2026-08-09",
  "laptopBranch": "codex/calendar-w11-cutover",
  "laptopHead": "b32cb0d05db5feb78a5671380d3fc0836dd2b970",
  "observedOriginMain": "3153b06d18d829ff5150e76bc1d92cb0124f9469",
  "coordinationTrigger": "TR-DESKTOP-ROUTE-BENCHMARK-START",
  "coordinationStatus": "ACKNOWLEDGED",
  "coordinationPublishedSha": "e96b991d2a4f54d4ae21cb4c1dc2c8b9fbbca2ce",
  "desktopAckMergeSha": "06d1470cbd3cea3c5229076659a5f303c0c89ff3",
  "evidencePolicyMergeSha": "f27fdb1b39ff64d262c8b8301faba9b657c11354",
  "routeBenchmarkMergeSha": "b0a230f14fcc6ca8f002d92947fe4b7300d48dcb",
  "routeBenchmarkConclusion": "SCENARIO_ROUTING",
  "sealedHoldoutConclusion": "PASS",
  "publicResponseEvaluationConclusion": "PASS",
  "laptopAutomaticEnvironmentReview": "PASS",
  "laptopAutomaticEnvironmentOpenBlockerCount": 0,
  "desktopAutomaticEnvironmentReview": "PASS",
  "desktopAutomaticEnvironmentOpenBlockerCount": 0,
  "approvedDirtyEntryCount": 376,
  "w11ReleaseTrigger": "PENDING_BASELINE_CLOSURE",
  "externalMutationCount": 0
}
```

2026-08-07 continuation snapshot：後續session必須直接使用既有
`D:\my-project\my-mobile-secretary\var\worktrees\calendar-w11`，不得建立另一個worktree或重新checkout
`codex/calendar-w11-cutover`。signed HEAD為`b32cb0d05db5feb78a5671380d3fc0836dd2b970`；唯讀status共325項
既有核准dirty entries（tracked 150、untracked 175），不得reset、stash、clean、restore或覆蓋。第十五次及
更早baseline、counts、runtime generation與重開機前receipts都已永久失效；Automatic review零blocker前不得
建立新窗口。provider evidence、兩台review、24小時／20筆本人真實LINE text inbound與closure audit完成前
維持非READY，不得commit、push、PR、merge或啟動桌電production lane。

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

- 2026-08-03 baseline 8 因第一筆 route-itinerary conversation production 修正標為
  `INVALIDATED_BY_ROUTE_ITINERARY_REPAIR`。本窗口新增 LINE evidence 已發現 system-place entity/action
  誤攔截、無 typed route 的假成功及明確交通安排落入 UNKNOWN；完成最後修正、runtime refresh 與
  官方 LINE E2E 後必須重建全新 24 小時／至少 20 筆本人真實 LINE text inbound baseline。
- route-itinerary production repair 已完成 typed intent／handler／catalog、entity-operation precedence、
  optional transport offer、activity adjustability、provider-neutral mode、單一 internal departure node、
  actor/scope pending workflow 與 V102 owned FK。focused 38／38、migration／RLS／provider／actual-entry
  17／17、neighbor／catalog／RLS／REST／LINE 145／145 PASS。第一輪 root 僅發現 clarification inventory
  未登錄新增 call sites；分類後 focused 17／17 與第二輪 fresh root 2,003／2,003（21 opt-in skipped）PASS，
  耗時 576.6 秒。runtime refresh、官方 LINE E2E 與新 baseline 尚未完成，不得標 READY。
- route actual-entry 另完成 20-sample latency／mutation hard gate，P95 ≤ 1,000 ms、每筆一個 top-level
  plan、零可見子活動、零交通 departure node、零 internal identifier 洩漏；test class 7／7 PASS。納入
  此 test-only gate 後最終 fresh root 2,004／2,004（21 opt-in skipped）PASS，耗時 603.9 秒。第一次
  runtime baseline 因正式停機執行此 gate 而中斷，禁止沿用；重新 refresh／LINE E2E 後另建新起點。
- 最終 runtime 於 2026-08-03 21:18:38 +08:00 刷新，健康 shared PostgreSQL／Redis 未重建，Dispatcher
  DISARMED；官方 LINE→ngrok→Spring Boot probe connected。第九次 aggregate-only baseline 於
  2026-08-03 21:20:53 +08:00 以 `REPEATABLE READ READ ONLY` transaction 建立，起始 inbound／text／
  outbound 皆 89，decision trace 89（SUCCEEDED 44、CLARIFICATION 42、FALLBACK 3、FAILED 0），Flyway
  V102。notBefore 2026-08-04 21:20:53 +08:00，closure 至少需本人 text inbound 109；未讀私人原文、
  payload、external message ID 或 UUID。時間／流量與 closure audit 未完成前不得標 READY。
- 使用者要求手動 LINE 測試前真正重啟主服務；已停止舊 8080 managed process並驗證 listener 消失，再
  啟動新程序。第一次新程序雖 health UP，但 ngrok 未運行且官方 probe 404；重跑正式啟動流程後，
  `dev-status.ps1 -ExternalLineProbe -VerboseOutput` exit 0，LINE→ngrok→Spring Boot connected，資料容器
  未重建、Dispatcher DISARMED。第九次窗口不沿用；第十次 baseline 於 2026-08-03 22:14:05 +08:00
  建立，起始 inbound／text／outbound 90、trace 90（SUCCEEDED 45、CLARIFICATION 42、FALLBACK 3、
  FAILED 0）、Flyway V102；notBefore 2026-08-04 22:14:05 +08:00，closure count ≥110。
- 2026-08-04 第一筆 warm system-place public reply production 修改已將第十次 baseline 標為
  `INVALIDATED_BY_SECRETARY_TONE_HARDENING`（先前的 warm system-place WIP 已包含於本次失效）。failure-first 以「知道某捷運站嗎」重現舊制式地址／分類／
  mutation-disclaimer 回覆；修復後必須重新跑 public boundary、system-place neighbor、actual LINE entry、
  fresh root、runtime refresh／官方 LINE E2E，才能建立下一個 24h／20 inbound baseline。
- 2026-08-04 V105/V106 仍屬同一 secretary-tone hardening production window；第十次 baseline 繼續維持
  `INVALIDATED_BY_SECRETARY_TONE_HARDENING`，不得重新起算。V105 保存 actor/workspace-owned typed restaurant
  guidance draft，每輪只問一題且不代表 provider booking/payment；V106 僅在明確 future-facing 指令保存
  bounded response style，一般稱讚 style 保持 NULL。最新 fetch 後 origin/main 仍為 `132308e...`、upstream
  migration latest V93，V105/V106 無 collision。需等最後 production fix、fresh root、runtime refresh 與
  官方 LINE E2E 成功後才可建立新 24h／20 inbound baseline。
- 2026-08-04 V105/V106 最終本地 gate：catalog/sealed 41（1 opt-in skipped）、API/LINE/replay 115／115、
  expanded actual-entry/RLS/quote/restart/privacy/latency 117／117 PASS；20-sample secretary P95 131 ms／
  max 144 ms、system-place P95 78 ms／max 91 ms，business mutation 均為 0。第一次 fresh clean root 的
  2,049 tests 找到 5 個 stale test data/oracle，未改 production；精準 12／12 後第二次 fresh clean root
  2,049 tests／0 failure／0 error／21 opt-in skipped PASS，耗時 508.6 秒。baseline 仍維持
  `INVALIDATED_BY_SECRETARY_TONE_HARDENING`，等待 runtime refresh 與官方 LINE E2E 後另起新窗口。
- 2026-08-04 09:50 +08:00 runtime refresh：重開機後既有 `mms-postgres`／`mms-redis` 皆為 Exited；未刪除、
  重建、改名或清資料，直接啟動並確認 healthy。`dev-start.ps1 -SkipDocker` exit 0，service generation
  `4198a8d30ce243a190d3845a0ae0fe6b`、Flyway V106；`dev-status.ps1 -ExternalLineProbe -VerboseOutput`
  exit 0，main UP、LINE→ngrok→Spring Boot connected、Dispatcher DISARMED/failure-isolated。
- 第十一次 monitoring baseline 已於 `2026-08-04 09:53:46.582781 +08:00` 以 PostgreSQL `REPEATABLE READ
  READ ONLY` transaction 建立，notBefore `2026-08-05 09:53:46.582781 +08:00`。起始 inbound／text／outbound／
  decision trace 均為 91，trace outcome SUCCEEDED 46、CLARIFICATION 42、FALLBACK 3、FAILED 0；task 1、
  legacy schedule 4、Calendar plan 1、intent issue 60、pending question 1、pending repair 0、pending restaurant
  guidance 0、Flyway V106。snapshot 僅含 aggregate metadata，未讀取私人原文、payload、external message ID、
  UUID 或 secret；closure 至少需 inbound/text 各 111，新增 20 筆須為 startedAt 後的本人真實 LINE text turn，
  synthetic probe、focused test 與 provider benchmark不得計入。完成 24 小時與 mutation/privacy/latency audit
  前，W11 維持非 READY。
- 第十一次 baseline first checkpoint：`2026-08-04 09:56:19.553055 +08:00`（elapsed 152 秒）服務與官方
  LINE 路徑仍 connected；startedAt 後新增 inbound／text／outbound／trace 均為 0，故沒有把 external probe
  或 synthetic traffic 計入真實樣本。task／legacy schedule／Calendar plan delta 均為 0；pending question 1、
  pending repair 0；aggregate-only diagnostic/identifier、bulk-missing、consecutive duplicate outbound 候選
  均為 0。此 checkpoint 不構成 closure，仍須滿足 notBefore 與 20 筆真實 text inbound。
- 第十一次 baseline second checkpoint：`2026-08-04 09:57:57.594623 +08:00`（elapsed 251 秒），
  time gate=false，新增真實 LINE text inbound／outbound／trace 仍為 0；task／legacy schedule／Calendar plan
  delta 均為 0，diagnostic/identifier、bulk-missing、consecutive duplicate outbound 候選均為 0。此為同一
  external wait condition 的第三個 goal turn；在 notBefore 與 20 筆真實 inbound 未到達前無法完成 release
  closure，不能以 synthetic traffic、focused tests 或縮短觀察窗取代。
- Phase D closure audit 發現 Booking skill 安裝版三個必要檔案缺失，已依正式權限流程精確同步；conversation
  repo／installed 4 檔與 Booking repo／installed 3 檔經 normalized-newline semantic comparison 7／7 equal。
  使用一次性 TEMP Python venv（不修改全域 Python）執行官方 `quick_validate.py`，四份 skill 均回傳
  `Skill is valid!`。Booking 變更仍只限 final public boundary／single-question 規則，沒有任何 provider、
  訂位、付款、取消或瀏覽器 mutation。

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
- 第五次 monitoring baseline 狀態為 `INVALIDATED_BY_CONVERSATION_HARDENING`。原先於
  `2026-08-02T11:40:13.553251+08:00` 以 PostgreSQL `REPEATABLE READ READ ONLY` transaction 建立，只讀
  metadata count，不讀 LINE 原文、payload、外部 message ID、UUID 或 secret，也沒有 mutation。startedAt
  為 `2026-08-02T11:35:17.5842273+08:00`，notBefore 為
  `2026-08-03T11:35:17.5842273+08:00`；起始 LINE inbound／text／decision trace 均為 32，trace outcome 為
  CLARIFICATION 11、FALLBACK 2、SUCCEEDED 19，legacy `schedule_item` 4、`calendar_plan` 0、`intent_issue` 22、
  Flyway V97。該窗口已因本輪第一筆 conversation hardening production code 修改失效，不得再以 LINE
  inbound/text 數量或時間到達宣告 closure。原出口曾要求至少各 52、其中至少 20 筆為 startedAt 後的真實本人文字 turn，
  legacy `schedule_item` delta=0，並完成新 trace、Calendar V2 route、scenario、privacy 與 latency 稽核；
  synthetic probe、focused test 與 provider benchmark 不得計入。
- 尚未完成：coordination commit／push／merge（GPG pinentry timeout，且本機 `gh` 未登入）、桌電 ACK、
  Google／TDX 真實 benchmark、sealed holdout，以及第五次 baseline 的 24h／20 inbound closure audit。

### 2026-08-02 conversation hardening local gate

- 唯一 public response boundary 已完成本地 focused／neighbor 驗證；三個 seeded failure 先以
  38 tests／3 failures 重現，修復後擴大至 138／138 PASS。
- V98 `conversation_pending_question` 已加入 actor/workspace/channel/scope、focus/workflow、stable
  question code、revision、expiry、inbound HMAC 與 FORCE RLS；actual-entry／replay／NOBYPASSRLS gate
  已通過。另新增 capability-specific `schedule_clarification_draft` typed columns；新 School Transport
  寫入不再保存 JSON payload。active pointer、明確 workflow 切換、parallel draft zero unintended mutation
  與可信 LINE quote-over-active 已分別通過 26／26 neighbor、135／135 擴大 gate、24／24 quote/reference。
- Phase C 已動態覆蓋 executable intent catalog／handler contract，並分類 55 個 source／158 個
  clarification occurrence。Phase B 的無 active pointer 多草稿單題選擇、精確 resume、selection/active cancel、
  correction/expiry 與 generic new-action bypass 已完成 production code，19／19 typed-state unit 與
  81／81 sealed/catalog gate PASS；但 Docker
  engine 無法啟動，新增 actual-entry/RLS 尚未重跑。Root regression、官方 LINE
  E2E、sealed holdout、latency 與新 24h／20 inbound baseline 均未執行，因此 W11 維持非 READY。
  因此 H1–H7 與 W11 均不得標 release PASS。
- 最新 fetch 驗證 `origin/main=132308e42d8a4c5dcd4929372a885fe1db66fd8a` 仍為 signed/current HEAD
  ancestor，merge-base 精確相同，且無 V98 collision；production ownership contract 未失效。Docker
  Desktop 則持續 `starting`、API 500、bundled ISO 未掛載且無 daemon；安全的 scoped restart 均已耗盡，
  未執行全域 WSL shutdown 或 Docker data reset。此 infrastructure blocker 不得被解讀為產品測試 PASS。
- 主機重開後 Docker Desktop engine 已恢復；Testcontainers actual-entry／RLS gate 10／10、擴大
  LINE／REST／catalog／sealed／RLS／actual-entry gate 232／232 PASS。首次 fresh root 1,929 tests 找到
  8 個 failure；修正 UNKNOWN public-safe mapping、typed pending decision-period answer 與過期 contract fixtures 後，
  受影響 focused／neighbor 108／108 PASS，第二次 fresh root 1,929 tests／0 failure／0 error／21 skipped
  PASS，耗時 355.8 秒。
- Runtime generation `92810288c54644578d8ff88b3fb52f85` 已於
  `2026-08-02T22:41:58.9354994+08:00` 以 Java 21、`dev-start.ps1 -SkipDocker -SkipDispatcher`
  刷新，沿用 labels 已確認的 shared-persistent PostgreSQL／Redis，未刪除或重建資料容器，亦未啟動
  Dispatcher production lane。`dev-status.ps1 -ExternalLineProbe` exit 0：main UP、PostgreSQL／Redis
  healthy、LINE connected；官方 LINE→ngrok→Spring Boot probe aggregate elapsed 6,213 ms。
- 第六次 monitoring baseline 已於 `2026-08-02T22:44:37.945719+08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction 建立；notBefore 為
  `2026-08-03T22:44:37.945719+08:00`。起始 LINE inbound／text／decision trace 均為 54，trace outcome
  為 CLARIFICATION 22、FALLBACK 3、SUCCEEDED 29，legacy `schedule_item` 4、`calendar_plan` 1、
  `intent_issue` 38、Flyway V98。快照只讀 aggregate metadata，未讀出私人原文、payload、external
  message ID、UUID 或 secret；closure 至少需 inbound/text 各 74，且其中 20 筆須為 startedAt 後本人真實
  LINE text turn，synthetic probe、focused test 與 provider benchmark不得計入。
- 2026-08-03 根據第六次窗口內匿名化 LINE issue／latency aggregate 啟動 feedback 當輪修復、重複
  UNKNOWN recovery 與統一秘書總覽 production 修正；因此第六次 baseline 狀態改為
  `INVALIDATED_BY_SECRETARY_CONVERSATION_REPAIR`，不得用於 release。完成最後一筆 production 修正、
  runtime refresh 與官方 LINE E2E 後，必須另建全新 24 小時且至少 20 筆真實 LINE text inbound baseline。
- 2026-08-03 依使用者既有產品決策補上 531 筆 immutable system-owned public-place snapshot，涵蓋捷運、
  台鐵、高鐵、機場、商港、縣市政府與中大型遊樂園；解析順序為 custom place → system catalog → external
  provider，查詢全程 read-only。failure regression 1／1 先紅；修復後 focused 19／19 與 LINE actual-entry
  1／1 PASS，custom place 零 mutation、internal category 零洩漏。這仍屬 production 修正，因此既有
  monitoring window 持續失效，且 neighbor／fresh root／runtime／官方 LINE／新 baseline 尚未完成。
- 2026-08-03 system-place 後續 gate：neighbor/catalog 36／36、受影響 Calendar/feedback/pending 49／49、
  expanded conversation 180／180、actual-entry/RLS/完整 LINE 46／46 PASS。第一次 fresh root 1,961 tests
  找到 6 failures；修正 secretary overview 不攔截 recommendation/analysis、Clock-stable Calendar V2 cutover
  read path、feedback acknowledgement、repair draft independent mutation bypass與舊 ordinal destructive
  reference invalidation後，第二次 fresh root 1,963 tests／0 failure／0 error／21 skipped PASS，耗時 485.0 秒。
- Java 21 fresh runtime generation `ea8973484e5c4fecb0782a22b3d318a4` 於
  `2026-08-03T10:36:14.4862901+08:00` 啟動，沿用已確認的 shared-persistent PostgreSQL／Redis，未刪除或
  重建容器，Dispatcher 維持 DISARMED。`dev-status.ps1 -ExternalLineProbe -VerboseOutput` exit 0：main UP、
  PostgreSQL／Redis healthy、LINE→ngrok→Spring Boot connected、Dispatcher optional-unavailable/failure-isolated。
- 第七次 monitoring baseline 於 `2026-08-03T10:39:25.1645200+08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction 建立，notBefore 為 `2026-08-04T10:39:25.1645200+08:00`。
  起始 LINE inbound／text／decision trace 均為 71，trace outcome 為 SUCCEEDED 38、CLARIFICATION 30、
  FALLBACK 3、FAILED 0，legacy `schedule_item` 4、`calendar_plan` 1、`intent_issue` 47、Flyway V99。
  snapshot 僅含 aggregate metadata，未讀出私人原文、payload、external message ID、UUID 或 secret；closure
  至少需 inbound/text 各 91，新增 20 筆須為 startedAt 後本人真實 LINE text turn，synthetic probe、focused
  test 與 provider benchmark均不得計入。W11 在 24 小時與全部 public/privacy/mutation/latency gates 完成前仍非 READY。
- baseline開始後且尚無新 inbound時補取 mutation/pending起始值：task 1、legacy schedule 4、Calendar V2
  plan 1、pending question 1、pending repair 0；closure須核對 exact intended mutation、zero unintended
  mutation及 pending preservation，且不讀取 pending內容。
- 第六次 baseline 起點後至 fresh runtime 前的 17 筆本人 LINE text 已做 aggregate-only audit：17 outbound、
  9 SUCCEEDED、8 CLARIFICATION、8 clarification issue、1 feedback issue；diagnostic/identifier leak、
  multiple-question candidate、bulk-missing candidate均為 0。公共地點需求涵蓋捷運、高鐵、台鐵／車站類，
  沒有輸出私人原文。20-sample deterministic secretary actual-entry latency 為 P95 146 ms、max 146 ms、
  business mutation 0（hard threshold 1,500 ms）。
- 第七次 baseline 已於本輪第一筆 system-place conversation production 修正時標為
  `INVALIDATED_BY_SYSTEM_PLACE_CONVERSATION_REPAIR`。最新14個真實LINE turn的匿名化 evidence顯示
  10個UNKNOWN、第四輪澄清循環、system catalog未接Calendar V2及model P95約13.3秒；修正完成後必須
  fresh root、runtime refresh、官方LINE E2E並另建24h／20 inbound baseline，舊窗口不得延用。
- Current system-place repair 以 typed Java resolution 區分唯一命中、同一大型地點多個實體點與跨縣市
  不同實體；custom place 優先。唯一命中直接套用到 Calendar typed snapshot；大型地點多點延後到最終
  materialization，依當時可驗證限制選點並公開選點理由；跨縣市只問一個縣市問題。V100/V101 的
  `public_place_lookup_draft` 只保存 actor/workspace/channel/scope、workflow/question/revision/fencing、
  stable catalog keys 與 Calendar draft reference，啟用並強制 RLS，不保存 raw LINE text 或 generic JSON。
  Calendar draft 先吸收同輪已驗證 slots，縣市答案完成 draft 與單一 Calendar plan 在同一 transaction；
  feedback/meta/read-only 不消耗 pending。缺座標時回報 route evidence 不足且 estimator call=0。
- Current verification receipts：failure regression 17 tests／1 failure／1 error；修復後 28／28、31／31、
  actual-entry/RLS 9／9、neighbor/RLS/latency 62／62、correction/custom precedence 13／13，以及最後相依
  focused 18／18 PASS。20 個 warm system-place actual-entry samples 為 P95 120 ms、max 136 ms、模型／token
  使用 0、business mutation 0。conversation repo／installed 的 SKILL、三份 references 與 openai metadata
  normalized-newline semantic comparison 5／5 equal；repo conversation、installed conversation、Booking
  三份官方 validator 皆 VALID。最後 production 變更後的完整 catalog／RLS／actual-entry／sealed、fresh
  root、runtime／官方 LINE E2E 與全新 baseline 尚待執行，因此前次 root/runtime receipt 均不得用來標 READY。
- 最後 production 版本已完成 neighbor/catalog/public-safety/sealed 101／101、V100/V101/RLS/actual-entry/
  LINE/API/latency 53／53 PASS。第一輪 fresh root 1,988 tests 找到 structured event 被 place shortcut 攔截、
  缺地址 custom place 公開 `null` 並遮蔽 domain guidance 兩個 failure；修正後精準 17／17、前置 gates 全數
  重跑，第二輪 fresh root 1,990 tests／0 failure／0 error／21 opt-in skipped PASS，耗時 614.5 秒。
- Java 21 runtime generation `3ea45cdb209147fcac76c31207d71b66` 已於
  `2026-08-03T16:37:21.0888019+08:00` 刷新；官方 `dev-status.ps1 -ExternalLineProbe -VerboseOutput`
  exit 0，主服務 UP、PostgreSQL／Redis healthy、LINE→ngrok→Spring Boot connected，Dispatcher DISARMED
  且 failure-isolated，未啟動桌電 production lane或重建資料容器。
- 第八次 monitoring baseline 於 `2026-08-03T16:40:36.535997+08:00` 以 PostgreSQL `REPEATABLE READ
  READ ONLY` transaction 建立，notBefore 為 `2026-08-04T16:40:36.535997+08:00`。起始 LINE inbound／
  text／outbound 均為 85；decision trace 85，outcome 為 SUCCEEDED 41、CLARIFICATION 41、FALLBACK 3、
  FAILED 0；task 1、legacy schedule 4、Calendar plan 1、intent issue 59、pending question 1、pending repair 0、
  pending public place 0、Flyway V101。快照只含 aggregate metadata；closure 至少需 inbound/text 各 105，
  新增 20 筆須為 startedAt 後本人真實 LINE text turn，且 24 小時、privacy/mutation/latency gates 全綠。
- 2026-08-04 system-place knowledge／point-query repair：preflight fetch 後 `origin/main` 仍落後 signed
  base 39 commits，migration ownership 維持筆電且當下 latest 為 V106，因此使用 V107 擴充 typed
  `READ_ONLY_MULTIPOINT` constraint，未碰桌電 lane。Failure-first 20／2 red；修復後 focused 30／30、
  catalog/RLS/Calendar/route/LINE expanded 83／83 PASS；最後 production版含 sealed/latency為89／89。
  Knowledge actual-entry 20 samples P95 64 ms、max 73 ms、model/token 0、business mutation 0。第一輪
  root 2,061 項只發現 clarification inventory count未登記；補登後精準24／24、第二輪 fresh root
  2,061／2,061、21 opt-in skipped PASS，耗時608.8秒。Runtime generation
  `234a8bd6b14e401083797b80394a0208`、Flyway V107、main UP、官方 LINE E2E connected。
- 第十二次 baseline 於 `2026-08-04 12:33:52.555708 +08:00` 以 aggregate-only read-only snapshot建立：
  LINE text inbound/outbound/trace皆94；task 1、schedule 4、Calendar plan 1、custom place 1、pending
  question 1、pending multipoint browse 0。closure需 elapsed≥24h且至少20筆新的本人真實LINE text inbound；
  synthetic probe／tests不得計入，門檻完成前仍非 READY。
- 2026-08-04 最新本人 LINE route-itinerary evidence 證明「規劃／安排」分流不一致、公開列出的桃園捷運
  點位無法精確回讀，且明確起訖交通行程首輪沒有呼叫 TDX／Google route provider。failure-first
  17 tests中14 PASS、2 failure／1 error，與實際 evidence一致。第十二次 baseline現標為
  `INVALIDATED_BY_CONVERSATION_HARDENING`，禁止沿用原窗口或 runtime receipt 宣告 stable／READY。
- 修復版已統一規劃／規畫／安排語意、公開 point label round-trip與 standalone route首輪 provider-first。
  V108只保存 typed provider status/mode/time-role/retrieved-at與 fencing；unavailable保留／typed retry前零
  Calendar plan mutation，成功後exactly one materialization。preflight涵蓋 direct overlap與前後銜接，
  active route focus優先於其他 pending workflow。TDX首末哩改為walking-only；Google公開 attribution，
  provider strategy維持policy-driven，沒有Google live key與代表性雙 provider evidence前不切換預設。
  重開機後 current-version staged gate為 focused/actual 14／14、neighbor/migration/RLS 17／17、provider
  15／15、catalog/public/LINE client 32／32、REST/LINE/sealed 91／91，合計169／169；skill repo/installed
  semantic 7／7 equal且validator 4／4 valid。全新 clean root regression 2,074 tests／0 failure／0 error／
  21 opt-in skipped PASS，耗時654.0秒。runtime generation `8836acc791b14607b933333a0ecabc99` 已刷新，
  main UP、PostgreSQL／Redis healthy、Dispatcher DISARMED/failure-isolated，官方LINE E2E connected；
  DEV_RUNTIME與LINE_E2E fresh receipt皆MATCH。
- 第十三次 baseline 於 `2026-08-04 17:36:50.492339 +08:00` 以 PostgreSQL `REPEATABLE READ READ ONLY`
  aggregate transaction建立，notBefore為 `2026-08-05 17:36:50.492339 +08:00`。起始LINE inbound／text／
  outbound／decision trace皆98；task 1、legacy schedule 4、Calendar plan 1、custom place 1、pending question 1、
  pending public-place browse 1、Flyway V108。未讀私人原文、external message ID、UUID、地址或payload；
  closure需text inbound至少118且elapsed≥24h，synthetic probe／tests／provider benchmark不得計入。
- 第十三次 baseline 已於 2026-08-04 的 route-origin/context production hardening 開工前標為
  `INVALIDATED_BY_ROUTE_ORIGIN_CONTEXT_HARDENING`。其98筆起始值、notBefore與runtime receipt均不得用於
  release；完成最後production修正、fresh root、runtime refresh與官方LINE E2E後必須建立全新窗口。
- route-origin/context hardening staged receipt：branch／signed base 維持
  `codex/calendar-w11-cutover`／`b32cb0d05db5feb78a5671380d3fc0836dd2b970`，重新 fetch 後 origin/main
  `74abfa7d7fa8b7412d64f77eff86307a6e65a949` 未碰本 lane production/schema，remote latest V93；筆電
  reservation 後新增 V109 HOME preference與 V110 typed journey kind，沒有修改既有 migration或啟動桌電
  production lane。Phase 2 focused/actual-entry與 Phase 3 HOME/RLS/replay/typed journey targeted gates 已綠；
  expanded、fresh clean root、runtime與官方 LINE E2E 仍待最後 production version重跑，因此本段只是 local
  staged evidence，不是跨 lane READY／MERGED receipt。
- route-origin／context hardening final local gate：expanded focused／neighbor／catalog／inventory／
  migration／RLS／actual-entry 74／74 PASS，REST／LINE webhook／replay／sealed／privacy 123／123 PASS；
  fresh `scripts\mvn-safe.ps1 -Clean test` 2,091 tests、0 failure／error、21 opt-in skipped PASS，耗時
  692.5 秒。runtime generation `8aee51c77c0b4bc38ffe1a78210d800a` 已刷新，main UP、PostgreSQL／Redis
  healthy、Dispatcher DISARMED/failure-isolated；官方 LINE→ngrok→Spring Boot connected，fresh
  DEV_RUNTIME／LINE_E2E receipt 均 MATCH。repo／installed conversation skill semantic 4／4 equal，
  skill-creator validator 2／2 valid。這些是筆電 local safe-gate evidence，不是桌電 ACK、跨 lane READY、
  MERGED 或 release receipt。
- 第十四次 aggregate-only baseline 於 `2026-08-04 20:57:17.151654 +08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction 建立，notBefore 為
  `2026-08-05 20:57:17.151654 +08:00`。起始 LINE inbound／text／outbound／decision trace 皆 100；trace
  outcome 為 SUCCEEDED 54、CLARIFICATION 43、FALLBACK 3、FAILED 0；task 1、legacy schedule 4、Calendar
  plan 1、intent issue 61、custom place 1、pending question 1、pending public-place browse 1、active HOME
  preference 0、pending Calendar draft 3、Flyway V110。未讀或保存私人原文、external message ID、UUID、
  地址、payload 或 secret；closure 需 elapsed ≥24h 且 LINE text inbound ≥120，新增 20 筆必須為
  startedAt 後本人真實 turn，synthetic probe／tests／provider benchmark 不得計入。完成 privacy／mutation／
  latency closure audit 前維持非 READY，且未發布任何 coordination state。
- 第十四次 baseline 已在 route reply／buffer／Google Maps production hardening 第一筆程式修改前永久標為
  `INVALIDATED_BY_ROUTE_REPLY_BUFFER_MAP_HARDENING`。原 startedAt、notBefore、100 筆起始 counts、120 筆
  closure threshold、runtime generation `8aee51c77c0b4bc38ffe1a78210d800a` 與其 runtime／LINE receipt 均不得
  再作 release evidence。完成最後 production 修正與所有 fresh gates 前，本 lane 維持非 READY，且不得
  建立或通知任何跨 lane READY／MERGED receipt。
- route reply／buffer／Maps staged receipt（2026-08-05）：origin/main 已驗證為
  `4fd39eb13f1a181d6522d4dac00ac3ff88b64fe1`，fresh SOURCE_WRITE／MAVEN／DOCKER_TEST 均 MATCH；本 lane
  reservation 後新增 V111 route operation preference與 V112 typed endpoint source，沒有改既有 migration。
  standalone 回覆已加入掃讀時間軸、所有風險行程名稱、typed停車／等車／提醒與可信 Maps boundary；
  focused 39／39、neighbor／catalog／inventory／REST／LINE webhook／replay／sealed 160／160 PASS；另以
  failure-first actual-entry 擋下 `(0,0)` Maps 連結後重跑通過。這不是桌電 ACK、跨 lane READY、MERGED
  或 release receipt。
- final laptop local receipt：fresh clean root 2,100 tests、0 failure／error、21 opt-in skipped PASS，耗時
  607.0 秒；runtime generation `bf8f440f5cea41f2b6a61a9c8e5d3061`，main UP、PostgreSQL／Redis healthy、
  Dispatcher DISARMED，官方 LINE E2E connected，fresh DEV_RUNTIME／LINE_E2E MATCH。Automatic review
  工具只允許 primary root evidence path，依 root dirty ownership未寫入或冒充；已登記 tooling backlog。
  在零-open-blocker review與全新24h／20筆本人真實LINE baseline完成前，本 lane仍非 READY。
- 上述 final laptop local receipt 已因 safe-time typed reschedule production 修正永久標為
  `INVALIDATED_BY_SAFE_TIME_ROUTE_RESCHEDULE`。原 2,100 tests、runtime generation
  `bf8f440f5cea41f2b6a61a9c8e5d3061` 與 DEV_RUNTIME／LINE_E2E receipt 均不得沿用；新的 clean root、runtime、
  官方 LINE E2E 與 Automatic review evidence 必須在最後一筆 production 修正後重新取得，本 lane維持非 READY。
- safe-time typed reschedule staged receipt：failure-first 已證明舊路徑無法執行 typed 後移；新路徑只允許
  前方 adjacency 缺口由 Java 計算、provider重新驗證與 hypothetical safety通過後原地更新 fresh route。
  provider unavailable、後方限制與 replay均有 actual-entry fail-closed evidence；11／11 actual-entry、42／42
  neighbor、99 tests catalog／inventory／REST／LINE／sealed PASS（1 opt-in skipped）。這不是 clean root、runtime、
  Automatic review、baseline或跨 lane READY receipt。
- safe-time final laptop receipt：fresh clean root 2,103 tests、0 failure／error、21 opt-in skipped PASS，耗時
  653.7 秒；runtime generation `6a1653e98d1f480698712d327750cde3`，main UP、PostgreSQL／Redis healthy、
  官方 LINE E2E connected，Dispatcher未啟用。重新 fetch後 `origin/main` 仍是
  `4fd39eb13f1a181d6522d4dac00ac3ff88b64fe1`；Automatic review仍只允許 primary root evidence path，
  本 lane依 ownership fail closed。零-open-blocker review與全新24h／20筆 baseline未完成，維持非 READY。
- safe-time completion audit另驗 direct overlap short answer、前後雙側風險與 stale node revision整筆 rollback；
  actual-entry 11／11 PASS。current-tree fresh clean root 2,105 tests、0 failure／error、21 opt-in skipped PASS，
  耗時 594.5 秒。此後只有測試／ledger變更，production binary未變；為釋放本 lane Maven claim，runtime已
  受管停止、資料庫保留，Automatic review解除後須再 refresh runtime／官方 LINE E2E才可開 baseline。
- worktree review tooling producer為 PR #23，head
  `09c75ae4aad3a2fc252dfc4cbe01224cd7f31c63`；目前 Draft、`CONFLICTING`／`DIRTY`、0 checks，尚未進
  `origin/main`。本 lane不修改 tooling ownership、不使用未合併 PR evidence、不寫 primary root；PR合併後
  必須重新 fetch並以正式入口產生 calendar-w11 Automatic review，否則 baseline與 READY維持 fail closed。
- PR #23 最新 head `c1163090f8b3d285dbb6e92442fe75ebe18340f8` 已解 conflict且 GitHub判定
  `MERGEABLE`；Fast、三個 Integration shards與 automated regression均 PASS。唯一 blocker是 tooling branch
  policy拒絕 `docs/agent-context/development-environment-preflight.md`，導致 Merge policy preflight／Merge
  policy失敗。PR仍是 Draft、未進 main，本 lane保持 claims released與 fail closed。
- PR #23 已於 2026-08-05 合併為 `origin/main` merge commit
  `3b01ba55fbb33467f1449190e840e06e01cc4990`。以該 commit 的 clean detached tooling runner對 registered
  `calendar-w11` target執行正式 Automatic review，已證明 evidence可寫入 target worktree；fresh
  SOURCE_WRITE／MAVEN均 MATCH。DOCKER_TEST則在 Docker Desktop受管啟動成功後，因 stopped
  `mms-postgres`／`mms-redis` 的 `NetworkSettings.Ports` 為空而被 validator誤判 published-port identity
  mismatch；唯讀 `HostConfig.PortBindings` 仍是正確 `5432:5432/tcp`／`6379:6379/tcp`，其他 labels、image、
  Compose identity與volume均相符。依 fail-closed零 container mutation，Automatic review維持 BLOCKED；另發現
  tracked review JSON保存 machine-local absolute paths，不符合公開環境證據隱私與跨 checkout可攜性，兩項均已
  登記 tooling backlog。repo／installed conversation與Booking skill完整 tree 10／10 normalized-newline相等，
  官方 quick validator 4／4 valid；Booking仍只有 guidance/read-only repair契約，沒有本輪 provider mutation
  authority。current surefire XML重算為2,105 tests、0 failure／error、21 skipped；production binary未變。
  Docker validator與portable review evidence修復前，不得啟動 runtime、開新 baseline或宣稱 W11 READY。
- Docker shared containers 已由使用者啟動並驗證 `mms-postgres`／`mms-redis` healthy；calendar worktree
  `dev-start -SkipDocker -SkipDispatcher` exit 0，runtime generation
  `06747128162542bcb086fd19e09c917f`，Dispatcher維持停用。官方 `dev-status -ExternalLineProbe` 回報
  main UP、PostgreSQL／Redis healthy、LINE connected。以 `origin/main@3b01ba55fbb33467f1449190e840e06e01cc4990`
  正式 tooling runner重跑 W11 Automatic review，READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／
  LINE_E2E／EXTERNAL_PROVIDER 7項全數 MATCH、open blocker 0、contract fingerprint
  `e43874472f69f3f66d949f02`，reviewedAt `2026-08-05T16:02:51.8443399Z`。此結果足以開啟本機真實LINE
  baseline，但review JSON仍含machine-local absolute path，不得以未具可攜性的檔案冒充tracked release evidence。
- 第十五次 aggregate-only baseline 已於 `2026-08-06 00:04:55.740380 +08:00` 以 PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為
  `2026-08-07 00:04:55.740380 +08:00`。起始LINE inbound／text／outbound／decision trace皆103；trace
  outcome為SUCCEEDED 56、CLARIFICATION 44、FALLBACK 3、FAILED 0；task 1、legacy schedule 4、Calendar
  plan 2、time node 3、route risk 0、route operation preference 0、reminder rule／occurrence 0、intent issue 62、
  custom place 1、pending question 1、pending public-place browse 1、active HOME preference 0、pending Calendar
  draft 4、Flyway V112。快照未讀或保存私人原文、external ID、UUID、地址、payload或secret；closure需
  elapsed ≥24h且LINE text inbound ≥123，新增20筆必須是startedAt後本人真實LINE文字turn，synthetic
  probe／tests／provider benchmark不得計入。closure與portable tracked review evidence完成前，本lane仍非 READY。
- 2026-08-06 upstream sync gate：remote沒有`origin/master`，正式上游`origin/main`已fetch至
  `5b652b88a89e1ee1336a60ce42b37fbca9da36c5`（PR #24 builtin place catalog）。signed HEAD
  `b32cb0d05db5feb78a5671380d3fc0836dd2b970`不是新main ancestor，merge-base為
  `132308e42d8a4c5dcd4929372a885fe1db66fd8a`；上游自signed HEAD起有15個dirty path overlap，最新
  PR #24直接重疊8個Conversation／Intent production、catalog與test paths。初始metadata盤點漏掉branch
  已tracked的Calendar V94；focused Flyway gate已推翻「上游V94沒有直接碰撞」判斷。public contract與path
  ownership已觸發`TR-PATH-OWNERSHIP-CONFLICT`
  `HARD_YIELD`。本輪只更新remote-tracking ref與durable ledger，沒有merge、fast-forward、stash、reset或
  覆蓋工作檔；253項dirty state不變。現在整合會使第十五次baseline永久失效並要求全套fresh gates與新
  24h／20 inbound窗口；完成本窗口後再整合則可保留目前監測證據，兩者不得混用或靜默決定。
- 使用者已於2026-08-06拍板立即整合並重建。第十五次baseline永久標為
  `INVALIDATED_BY_ORIGIN_MAIN_PR24_INTEGRATION`；其startedAt
  `2026-08-06 00:04:55.740380 +08:00`、notBefore `2026-08-07 00:04:55.740380 +08:00`、起始
  inbound／text／outbound／trace 103、closure門檻123、runtime generation
  `06747128162542bcb086fd19e09c917f`及相關runtime／LINE／Automatic review receipt全部失效。整合後
  必須重跑focused、neighbor、migration／RLS、actual-entry、full root、runtime、LINE、Automatic review，
  並另建24h／20筆本人真實LINE text inbound窗口；不得沿用任何舊count、窗口或generation。
- PR #24 migration已在fresh fetch與working-tree latest V112確認後，取得
  `repo/flyway-sequence/main/V113`及worktree source exclusive coordination，將尚未執行的新增catalog
  migration移至next available V113。Flyway operation `f86209ca-032b-4219-adaf-64234e406769`、source
  operation `2d6c333d-66fd-4d57-8f94-1fec65879030`均已釋放；既有Calendar V94與V95–V112未修改，重複版本0。
- PR #24 working-tree integration與local automated gates已完成：87個merge paths與three-way candidate hash完全
  相符，existing dirty ownership、branch與signed HEAD均未改寫；DB catalog public contract、typed clarification
  inventory與V113 migration已完成reconciliation。focused／neighbor／migration／FORCE RLS／actual-entry／
  REST／LINE／sealed／skill validation全綠；最終fresh clean root 2,114 tests、0 failure／error、18 skipped，
  耗時979.9秒。最後fetch後`origin/main`仍為`5b652b88a89e1ee1336a60ce42b37fbca9da36c5`。
  這只代表local automated gate；fresh runtime／官方LINE E2E／Automatic review／portable tracked evidence與新
  24h／20本人真實LINE text baseline尚未完成，baseline #15及舊generation／counts／receipts永久不可沿用，
  不得標READY、commit、push、PR或merge。
- runtime gate已以正式入口實跑並fail closed：ngrok launch與managed validator command contract互斥；
  `-NoNgrok` main health成功後又因linked-worktree clean build誤嵌primary root Git identity而判`STALE`並rollback。
  fresh DEV_RUNTIME／LINE_E2E均`ACTION_REQUIRED`，main/ngrok/Dispatcher未運行，shared Postgres／Redis healthy。
  兩個root cause與缺少`dev-environment-report.ps1`均已登記tooling backlog；工具修復進main並重新整合前，
  Automatic review與新baseline保持未啟動，不得以local full Maven PASS取代。

## 2026-08-06 tooling reintegration／rebuild receipt

- 2026-08-06 tooling reintegration：fresh fetch後`origin/main`為PR #26 merge
  `0eba57aab203b8cd861055b4105d2a31b37464ec`。PR #25／#26的22個純tooling路徑在寫入前均精確等於
  `origin/main@5b652b88`，因此只更新為新main版本；`pom.xml`保留本lane既有產品內容，只加入native Git與
  registered-worktree metadata anchor。branch／signed HEAD維持`codex/calendar-w11-cutover`／`b32cb0d…`，
  沒有reset、restore、stash、commit、push、PR或migration mutation。直接tooling regressions涵蓋Docker
  identity、managed lifecycle、worktree secret resolution與Maven identity共39 assertions PASS；clean build
  產生的`git.properties`已為`b32cb0d…`／commit count 239，linked-worktree identity blocker解除。
- 同輪full tooling umbrella不可標全綠：coordination kernel在umbrella內有兩次偶發失敗但獨立與
  handoff→kernel序列皆PASS；固定clean-main inventory預期387 classes不適用本lane 459-class dirty tree；兩支
  environment review fixture在深層linked-worktree產生超長GUID state path而失敗。其餘已執行tooling tests
  均PASS，這些限制已登記tooling backlog，不以放寬assertion或修改tooling ownership繞過。
- fresh MAVEN receipt為MATCH後執行`scripts\mvn-safe.ps1 -Clean test`，編譯與worktree identity成功，但
  Testcontainers在Docker Engine不可用時造成2,114 tests中0 failure／604 context errors／36 skipped，不能作
  產品回歸證據。fresh DOCKER_TEST先為`DAEMON_NOT_READY`，受限managed Docker Desktop入口確認固定identity、
  signer與`desktop-linux` context相符，但既有Desktop程序在180秒內仍無法通過`docker ps`，分類
  `DAEMON_TIMEOUT`；工具沒有kill／restart backend或修改container／volume。DOCKER_TEST恢復MATCH並重跑
  fresh clean root前，不得啟動runtime、LINE E2E、Automatic review或新baseline，本lane維持非READY。

## 2026-08-06 Docker恢復後fresh release-gate receipt

- 使用者重啟Docker Desktop後，首次fresh probe由WSL `CreateVm/HCS/0x800705aa`證明主機只剩0.38 GB
  可用實體記憶體；沒有停止其他session或使用者程式。記憶體恢復至19.93 GB後，正式
  `start-managed-docker-desktop.ps1`回READY／server 28.3.2；typed shared-infrastructure policy只start
  合約相符的`mms-postgres`與`mms-redis`，兩者healthy，零create／remove／replace／volume change。fresh
  DOCKER_TEST與MAVEN均MATCH。
- 隨後fresh `scripts\mvn-safe.ps1 -Clean test`為2,114 tests、0 failure／error、18 skipped PASS，耗時
  501.0秒；456份Surefire XML重算一致。`git.properties`仍精確為signed HEAD `b32cb0d…`／count 239。
- 正式`dev-start.ps1 -SkipDispatcher` exit 0，runtime generation
  `2085802c312e4df4885171d704ee6bd5`；Postgres／Redis healthy、main UP且SHA正確、ngrok受管運行、
  Dispatcher DISARMED／未運行。fresh DEV_RUNTIME與LINE_E2E均MATCH；官方
  `dev-status.ps1 -ExternalLineProbe -VerboseOutput`實際證明LINE→ngrok→Spring Boot connected，但整體exit 1，
  因新啟動dirty runtime被`dev-start`允許而`dev-status`仍要求version status CURRENT。此tooling contract矛盾
  已登記backlog，不把connected子結果冒充整體script PASS。
- 本輪Automatic review以正確worktree evidence path覆寫8/5舊PASS；六項實際使用capabilities均fresh MATCH，
  但openCount 3，outcome仍BLOCKED：sandbox DOCKER_TEST caller access denied、已成功重跑但recheckKind=MANUAL
  而無法自動關閉的monitor issue，以及缺正式authority receipt的EXTERNAL_PROVIDER issue。Agent嘗試自行建立
  process-local external receipt已被安全政策拒絕，沒有繞過。Automatic review為BLOCKED時不得建立新baseline、
  commit、push、PR、merge或宣稱READY。

## 2026-08-07 producer交付核對與fresh review

- fresh fetch後`origin/main@68b54c8`已發布producer-handoff automation及
  `TR-DESKTOP-ROUTE-BENCHMARK-START` READY receipt；桌電evidence lane可依receipt另行ACK，但本筆電仍是
  production唯一writer。這批PR未包含DIRTY runtime content fingerprint、Automatic review issue resolution或
  scoped external-provider authority，因此不解除本lane release blocker，也不需把其unrelated tooling paths覆寫
  進現有dirty product tree。
- calendar-w11 branch／signed HEAD仍為`codex/calendar-w11-cutover`／`b32cb0d…`，dirty 325項完整保留。
  host正式Automatic review於`2026-08-07T01:59:34.1552040Z`重跑；READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／
  DEV_RUNTIME／LINE_E2E六項均fresh MATCH，但outcome仍BLOCKED、openCount 4。原三項為sandbox Docker caller、
  external authority與MANUAL monitor issue；第4項由sandbox review的Git dubious-ownership失敗留下，雖sandbox
  MAVEN已fresh MATCH，host review仍無matching caller snapshot。fresh evidence已寫回
  `calendar-w11-laptop.json`；新baseline維持未啟動，舊baseline／runtime receipts持續永久失效。

## 2026-08-07 origin/main@9004d10 revalidation

- 重開機後shell與Git fetch已恢復；branch／signed HEAD仍為`codex/calendar-w11-cutover`／`b32cb0d…`，325項
  dirty ownership未變。`origin/main@9004d10`包含PR #34 sanitized evidence policy、PR #35桌電benchmark與
  PR #37狀態更新，但沒有DIRTY runtime fingerprint、issue resolution、scoped external authority或正式report入口；
  GitHub也沒有對應open tooling PR。
- 桌電benchmark正式結論為`INSUFFICIENT_EVIDENCE`：10次read-only query僅1次成功，Google HTTP 403，TDX有
  `NO_ROUTE`／HTTP 429／不支援`ARRIVE_BY`，freshness未證明。桌電Automatic review另有5個既有
  LINE_E2E／DEV_RUNTIME／DOCKER_TEST issue。這兩項與筆電Automatic review blocker都必須由各自owner產生
  Git-retrievable修復／evidence；calendar production lane不得自行擴張tooling、桌電runtime或external authority。

## 2026-08-07 PR #38 calendar-w11 integration receipt

- fresh `origin/main`為`a45fe8496259baa27f2085db865d0597f50a58b3`（PR #38）。branch／signed HEAD仍為
  `codex/calendar-w11-cutover`／`b32cb0d05db5feb78a5671380d3fc0836dd2b970`；325項既有dirty ownership
  （150 tracked／175 untracked）完整保留，未執行diff、reset、stash、clean、restore、commit、push或PR。
- 已整合production-content fingerprint、registered-worktree identity、capability-scoped Automatic review、
  typed issue lifecycle、正式report入口、scoped external-authority receipt與sanitized evidence。另補齊main既有
  producer-handoff engine／policy／workflow依賴；未修改calendar產品語意、migration或桌電runtime lane。
- focused tooling證據：authority 27、report 12、preflight 32、短路徑release-review 11、sandbox-safe
  service-version 20、worktree-review 13、producer engine 18、workflow contract 22 assertions PASS；tooling umbrella
  前10項通過後仍在既有dirty-tree inventory固定387（本lane 459）停止。深層release-review fixture仍須短temp
  root；兩項列於tooling backlog，不冒充umbrella全綠。
- 重開機後Docker Desktop尚未ready，`DOCKER_TEST=DESKTOP_NOT_RUNNING`。在fresh Docker／Maven clean root、
  runtime refresh、官方LINE E2E與Laptop Automatic review零blocker完成前，本lane仍為release blocked；桌電
  Google credential、TDX freshness與ARRIVE_BY evidence缺口不阻擋筆電coding，但阻擋最終READY與新baseline。

## 2026-08-07 fresh laptop stable-gate receipt

- repository-owned managed Docker Desktop入口啟動固定signed executable，`desktop-linux` server 28.3.2 ready；
  既有`mms-postgres`／`mms-redis` identity、port與volume contract相符，只由`dev-start.ps1` bounded start，
  沒有create／replace／remove／volume mutation。fresh host `DOCKER_TEST`與`MAVEN`均MATCH。
- `scripts/mvn-safe.ps1 -Clean test`正式通過：2,114 tests、0 failure／error、18 skipped、708.7秒。
  隨後以同一clean build啟動runtime；generation=`ea1e9b3d1f154268901cd2dadb368bc7`，Spring Boot UP、
  source=`VERIFIED_DIRTY`、Postgres／Redis healthy、Dispatcher DISARMED，官方LINE probe為
  `LINE -> ngrok -> Spring Boot connected`，`dev-status` exit 0。
- Laptop Automatic review於`2026-08-07T10:25:34.3668543Z`完成：contract
  `855de50e9699b02fab6b427e`，READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／LINE_E2E
  六項MATCH，5個歷史issue均typed FIXED，openCount=0、outcome=PASS；對應assertion PASS。evidence為
  `docs/exec-plans/evidence/development-environment/calendar-w11-laptop.json`，尚未進Git，因此不宣稱
  `-RequireTracked` release gate。
- 筆電產品／環境local gate已穩定，但整體W11仍不得READY：桌電provider evidence尚缺合法Google credential、
  TDX provider freshness timestamp與ARRIVE_BY支援證據；新24小時／20筆本人真實LINE text baseline仍未啟動，
  所有舊baseline、counts、generation與runtime receipt持續永久失效。

## 2026-08-07 desktop PR #39 consumption

- fresh fetch後`origin/main@699fc3e`已合併PR #39；四份desktop benchmark／environment evidence原先在本
  worktree不存在，已以main blobs新增，未覆蓋任何local檔。Desktop Automatic review為PASS、openCount=0，
  READ_ONLY與EXTERNAL_PROVIDER均MATCH。
- TDX本輪4次read-only query為4 success、0 failure，median 1,833 ms、nearest-rank P95 3,886 ms；但正式
  conclusion仍是`INSUFFICIENT_EVIDENCE`。blocking facts為：Google Routes沒有合法專用credential而零query、
  TDX成功回應沒有provider update timestamp可區分scheduled／real-time freshness、兩個ARRIVE_BY案例維持
  typed unsupported且沒有偷換DEPART_AT。
- 兩台Automatic review目前皆零open blocker，但provider benchmark不足仍阻擋整體W11 READY與新baseline；
  這不要求改變`TDX_PRIMARY`、不允許重用Places credential，也不授權任何booking／payment／provider mutation。

## 2026-08-07 desktop evidence integrity／coverage audit

- PR #39 cases／manifest／results schema與suite一致，caseSetHash已用本地SHA-256重算相符；10個匿名case、
  4次external query、external mutation 0，常見secret／personal identifier pattern為0，沒有raw payload。
- `origin/main@699fc3e`尚無本計畫allowlist中的`sealed-holdout/**`與`public-response-evaluation/**`。依雙機
  ownership，這兩份必須由desktop evaluator lane產生並合併；laptop production owner只能consume與驗證，
  不得用本機既有regression替代獨立黑箱evidence。

## 2026-08-08 desktop PR #41 evidence closure audit

- fresh `origin/main@b0a230f14fcc6ca8f002d92947fe4b7300d48dcb`已合併PR #41。benchmark、sealed
  holdout、public-response evaluation與Desktop Automatic review已以精確main blob同步；同步前舊
  PR #39檔案皆與`699fc3e` blob一致，新資料夾原本不存在，沒有覆蓋本lane變更。
- benchmark cases實際SHA-256為`29fcbdd2bc0c055317c14a3b779368128a38bd26cc0212762d63701b1c42b88b`，
  與manifest／results一致；35次benchmark route query加3次sealed holdout，external mutation 0。TDX
  與Google均有fresh matching READ_ONLY receipt；TDX誠實標示scheduled-only，兩provider的native
  `ARRIVE_BY`都有驗證，沒有互換time role或宣稱普遍最快。正式結論為`SCENARIO_ROUTING`。
- sealed holdout cases SHA-256為`2dd452cde0222a0ea96823ebd79e727788d56215e70dc9caf87f01476ce9185e`；
  evaluator於執行前凍結，3／3 PASS，2 success／1 typed NO_ROUTE，provider failure 0，time-role change與
  fabricated route均為false。public-response evaluation 5／5 PASS，internal identifier／secret／absolute path leak均0。
- 9份證據的email、UUID、LINE ID、secret、private IP與absolute-path pattern掃描均0，不含raw provider
  payload。Desktop Automatic review在桌電registered worktree為PASS、openCount 0、contract
  `855de50e9699b02fab6b427e`；筆電不冒充桌電target重跑identity-bound assertion。
- provider／獨立evaluator等待條件已解除。下一個gate是本版fresh Docker／Maven／runtime／官方LINE、
  Laptop Automatic review，並且只能在全數通過後開新24小時／20筆本人真實LINE text baseline。

## 2026-08-08 runtime refresh and caller-bound review blocker

- official `dev-start.ps1 -SkipDispatcher` exit 0；runtime generation為
  `994bfbb2991a4735afaca59b5db24cde`，Spring Boot UP，source `VERIFIED_DIRTY`，PostgreSQL／Redis
  healthy，Dispatcher DISARMED，ngrok running。host fresh DEV_RUNTIME／LINE_E2E均MATCH，
  `dev-status.ps1 -ExternalLineProbe -VerboseOutput` exit 0，官方`LINE -> ngrok -> Spring Boot` connected。
- Laptop Automatic review的本版capability recheck在host均MATCH，但本輪早先的sandbox DEV_RUNTIME
  preflight因Windows Docker config／named pipe權限留下`PREFLIGHT_CALLER_ACCESS_DENIED`。正式review
  因同caller issue無fresh ready snapshot而`BLOCKED`；不刪issue、不換StateRoot、不以host冒充sandbox。
- 這是tooling／execution-policy blocker，已登記`docs/tooling-backlog.md`。在tooling producer交付
  Git-verifiable修復並重跑Automatic review PASS/openCount 0前，不啟動新24小時／20筆baseline，
  `TR-CALENDAR-W11-MERGED`維持`PENDING`。

## 2026-08-08 PR #42 schema v4 review receipt

- `origin/main@e4a41c18eb52eb1b2a0129d8eb4d9d533e212c9a`（PR #42）已安全整合其13個
  exact tooling／test blobs；既有dirty產品內容未覆寫。managed-operation 18、environment-report 14、
  lifecycle-contract 16與service-version 20 assertions PASS。完整tooling umbrella在本lane因test inventory
  未覆蓋387個既有test classes而停，未降低assertion或竄改inventory。
- 受管restart建立generation `52515b82ab5249ee85b701924a1b0b93`；Spring Boot `VERIFIED_DIRTY`、
  PostgreSQL／Redis healthy、Dispatcher DISARMED，官方LINE E2E connected。READ_ONLY／SOURCE_WRITE／MAVEN／
  DOCKER_TEST／DEV_RUNTIME／LINE_E2E六項schema v4 host preflight均fresh `MATCH`。
- Laptop Automatic review於`2026-08-08T01:33:06.5846037Z`產生schema v4 evidence，contract
  `4e0e468fb5de192deb9f3419`。PR #42已將sandbox probe-only的DEV_RUNTIME與LINE_E2E access denial
  typed `FIXED`，但DOCKER_TEST仍為`PREFLIGHT_CALLER_ACCESS_DENIED`，因此outcome `BLOCKED`、openCount 1。
  不刪issue、不少報capability、不以host冒充sandbox；Docker review-only operation receipt與dev-start reuse
  generation邊界已登記tooling backlog。Automatic review達PASS/openCount 0前不啟動新baseline。

## 2026-08-08 PR #43 merge and ngrok ownership blocker

- 使用者已授權精確head `24dfd4a384d929a047f89a05dce9942be393a070`；PR #43 required checks全綠後
  合併為`714f8d60425a70282ad3ff2d082e473761be0c75`。10個exact tooling／test blobs已安全整合，
  PR #43 focused regression 24＋9＋19＝52 assertions PASS，既有dirty產品內容未覆寫。
- 第一次official `dev-start.ps1 -SkipDispatcher`因Docker daemon bounded timeout fail closed；fresh host probe
  隨後辨識matching shared containers為stopped，第二次official start只恢復既有containers，PostgreSQL／Redis
  已healthy，沒有重建、刪除或volume mutation。
- 同次start的新ngrok ownership receipt因command snapshot未匹配repository lifecycle contract而拒絕並rollback；
  目前Spring Boot／ngrok not running、Dispatcher DISARMED。不得手動啟動、冒充ownership或跳過receipt；
  Automatic review未重跑、baseline未啟動、`TR-CALENDAR-W11-MERGED`維持`PENDING`。此live blocker已登記
  tooling backlog，等待Git-verifiable修復。

## 2026-08-08 PR #46 WMI-unavailable live retest

- PR #46 head `233d2ccb71b666b5dd2b37e5d47d2b5611ed6e24`由使用者合併為
  `9b8fe68331afc0ef8d7089b8be6aa3a1b3bc5588`；6個exact tooling／test blobs已安全整合，
  managed-process 12與ngrok-start 16，共28 assertions PASS，receipt privacy assertion通過。
- official `dev-start.ps1 -SkipDispatcher`重跑仍在ngrok ownership publication fail closed：
  `Managed process identity snapshot was not ready within the bounded publication window.`，並完成startup rollback。
  以同一host caller查詢目前PowerShell自身PID亦只取得`READY／LIMITED_NATIVE`，沒有executable／command欄位，
  證明此host的WMI process identity來源不可用，並非單純ngrok PID或延遲。
- PostgreSQL／Redis維持healthy，Spring Boot／ngrok not running，Dispatcher DISARMED。不得手動啟動或
  放寬ownership contract；Automatic review未重跑、baseline未啟動、`TR-CALENDAR-W11-MERGED`仍PENDING。
  等待tooling producer交付可在WMI unavailable時提供exact identity的Git-verifiable修復。

## 2026-08-08 PR #47 mixed-receipt live blocker

- PR #47 head `5d3a7c947f568c9833440a430fafc0e78164f5a6`已合併為
  `e27e93bdc6e2a521dbdfaab9a8790ce2c81d4c3e`；4個exact tooling／test blobs安全整合。
  native exact、managed lifecycle與ngrok lifecycle共40 assertions PASS；same-caller native exact query在本host
  實際PASS且無sensitive output。
- official `dev-start.ps1 -SkipDispatcher`已跨過WMI／native identity gate，但在ownership replay scan遇到合法
  舊schema／其他類型receipt無`action`欄位，StrictMode回`The property 'action' cannot be found on this object.`
  並安全rollback。不得刪舊receipt、換StateRoot或略過replay scan。
- PostgreSQL／Redis healthy，Spring Boot／ngrok not running，Dispatcher DISARMED；Automatic review未重跑、
  baseline未啟動、`TR-CALENDAR-W11-MERGED`維持`PENDING`。typed mixed-receipt修復已派發tooling producer。

## 2026-08-08 PR #48 review-resolution idempotency blocker

- PR #48 head `6a4ae9e7a068afe360df3ed0d1b2a7429eb63c2c`已合併為
  `0902f2324d170e9db46002f9c918b9640f49cfa4`；4個exact tooling／test blobs已安全整合，focused
  53 assertions PASS，既有dirty產品內容未覆寫。official `dev-start.ps1 -SkipDispatcher`隨後成功，
  Spring Boot為`VERIFIED_DIRTY`、PostgreSQL／Redis healthy、Dispatcher DISARMED、官方LINE E2E connected，
  service generation為`f75aaf1de46c460e9263a1c9a62058f6`。
- 六項schema v4 host preflight均fresh `MATCH`。但Automatic review第一次仍輸出既有schema v4
  `BLOCKED/openCount 1`，唯一OPEN項為probe-only `DOCKER_TEST/PREFLIGHT_CALLER_ACCESS_DENIED`。為排除
  15分鐘receipt時效，已由official start刷新matching managed-operation receipts並立即重跑review。
- 兩次立即重跑皆在關閉同一typed issue時以`matching OPEN typed environment issue does not exist` fail closed；
  表示matching receipt已進入resolution流程，但同一輪或歷史重複項再次嘗試關閉已非OPEN的ledger項目。
  不刪issue／receipt、不換StateRoot、不停用replay、不放寬caller或generation matching。此exactly-once／
  idempotent review lifecycle blocker已派發tooling producer；Automatic review PASS/openCount 0前不啟動baseline，
  `TR-CALENDAR-W11-MERGED`維持`PENDING`。

## 2026-08-09 PR #49 historical managed-evidence renewal blocker

- PR #49 head `be008bc3f3c6517209645631c8c9c39a1c2f5ba5`已合併為
  `3567c2e642678adcdad516d8cb16c999ecd264ac`；三個exact tooling／test blobs已同步且focused
  34 assertions PASS。fresh capability receipts均MATCH，official start成功，runtime／LINE為current
  managed generation `f75aaf1de46c460e9263a1c9a62058f6`。
- live Automatic review揭露PR #49 fixture未覆蓋的第二種冪等情境：canonical DEV_RUNTIME／LINE_E2E
  caller-access issue已是FIXED，但resolution evidence屬舊contract
  `4e0e468fb5de192deb9f3419`、舊generation `52515b82ab5249ee85b701924a1b0b93`及舊snapshot。
  current contract為`786e05082c5e226466c84d7c`，故review正確重新分類OPEN並找到fresh current receipt；resolver
  卻只接受相同舊receipt的重播，拒絕current generation evidence renewal。
- Tooling修復必須驗證舊evidence的歷史結構與review-only authority，再以完整current
  repo／worktree／machine／caller／capability／operation／generation／contract／freshness／snapshot fences
  原子更新，並保留audit lineage；wrong scope、tamper、expired或operation participant仍fail closed。
  DOCKER真正OPEN項須在同一review後續正常處理。修復已派發WSL producer；在Git-verifiable handoff、
  Automatic PASS/openCount 0前，baseline不啟動且`TR-CALENDAR-W11-MERGED`維持`PENDING`。

## 2026-08-09 PR #50 historical renewal closure

- PR #50 head `f2bc038102c20a3cad1a7b514cf20598eb031d49`已合併為
  `0f86167f7cc9675e507a03e1933351472441d3c5`；四個exact tooling／docs blobs已安全同步，focused
  managed-operation test 64 assertions PASS，Calendar production dirty scope未觸碰。
- official restart建立current generation `9af14e7a90e34356b2a224eb48783965`；runtime為
  `VERIFIED_DIRTY`，PostgreSQL／Redis healthy，官方LINE E2E connected，Dispatcher DISARMED。
- Laptop schema v4 Automatic review reviewedAt `2026-08-09T04:58:50.1712856Z`、contract
  `786e05082c5e226466c84d7c`；六項capability全數MATCH，4項typed issues為FIXED、6項核准sandbox async
  limitation具current-contract evidence，最終`PASS/openCount 0`。PR #50已實際證明historical FIXED
  managed evidence renewal、current receipt immediate rerun與真正OPEN Docker closure可依序完成。
- environment blocker解除；baseline尚未啟動，`TR-CALENDAR-W11-MERGED`仍PENDING。下一步先稽核完整
  baseline前置gate，再建立全新24小時／至少20筆本人真實LINE text inbound evidence。

## 2026-08-09 sixteenth monitoring baseline started

- 前置gate稽核確認provider／sealed／public-response、focused／RLS／actual-entry／privacy／latency、2,114-test
  clean root、current runtime／官方LINE及兩台Automatic review均PASS；PR #50後無production change。
- 第十六次aggregate-only baseline於`2026-08-09 13:03:00.245014 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為`2026-08-10 13:03:00.245014 +08:00`，
  generation `9af14e7a90e34356b2a224eb48783965`。起始LINE inbound／text／outbound／decision trace皆106，
  outcome為SUCCEEDED 58、CLARIFICATION 45、FALLBACK 3、FAILED 0；closure門檻為text inbound至少126。
- 起始aggregate為task 1、legacy schedule 4、Calendar plan 2、time node 3、route risk 0、route operation
  preference 0、reminder rule／occurrence 0、intent issue 63、custom place 1、pending question 1、pending
  public-place browse 1、active HOME 0、pending Calendar draft 5、Flyway V113。沒有讀取或保存原文、外部ID、
  UUID、地址、payload或secret；synthetic probe／tests／benchmark不得計入本人20筆真實turn。
- baseline已因真實LINE route pending workflow/context transition misrouting永久標為
  `INVALIDATED_BY_CONVERSATION_CONTEXT_TRANSITION_MISROUTING`，`TR-CALENDAR-W11-MERGED`仍PENDING。
  不得沿用此窗口的startedAt、counts、generation或receipts；須完成修復、fresh runtime／LINE／Automatic
  review後另建24小時窗口。

## 2026-08-09 route pending／context-transition failure gate

- Aggregate-only live diagnosis確認LINE inbound／text／outbound／decision trace皆110；最新三個route turn
  validation PASS但皆為CLARIFICATION，provider未被呼叫。每次回答都建立新的`route.general-buffer` pending
  與Calendar draft，而不是恢復原workflow。
- Pending workflow ID實際指向Calendar draft，但root domain沿用不相干的active TASK；同scope累積4份eligible
  route drafts後，unique-current fallback fail closed，回答落回generic intake形成重問循環。
- 使用者已批准全服務共用context-transition契約：未完成操作存在時不得靜默切換；明確延續／修改／開始新操作
  要告知目前操作名稱，不明確時只問一個target-selection問題且zero mutation；quote優先、read-only插話保留
  pending，context切換本身不新增任何booking／payment／cancellation authority。
- 修復完成前release維持BLOCKED。必要gate包含failure-first、typed workflow/domain binding、route buffer自然
  回答、new-operation choice、restart／replay／quote／parallel draft、RLS、actual-entry、exactly-one／zero
  unintended mutation、fresh clean root、runtime／官方LINE E2E、Automatic review與全新baseline。
- Fresh fetch/preflight後`origin/main=3153b06d18d829ff5150e76bc1d92cb0124f9469`，本worktree migration
  latest為V113；Laptop Calendar／Conversation lane仍持有唯一Flyway ownership，Desktop evidence與Transport
  consumer無schema reservation。V114已保留給pending context choice的bounded typed question／safe-label欄位，
  禁止raw LINE文字、候選新操作內容或generic JSON。

## 2026-08-09 context-transition implementation progress

- 已新增V114 bounded pending context欄位與全服務`ConversationContextTransitionService`。模糊的新 executable
  operation在任何handler/provider/Calendar mutation前只問一題；明確延續恢復原route question，明確新操作
  保留舊draft並以公開safe label告知舊／新操作。
- 新操作成功後，domain/focus transition與舊pending pointer completion已放入同一transaction callback；generic
  rewrite不得丟失interrupted question。quote仍優先，read-only/meta command不消耗pending context。
- 目前focused／neighbor evidence：4條route actual-entry與10條domain/service/catalog PASS；整批route
  actual-entry、pending RLS與相關tests共35／35 PASS。完整validation與fresh release evidence尚未完成，
  `TR-CALENDAR-W11-MERGED`維持PENDING，任何第十六次baseline證據仍永久無效。

## 2026-08-09 context-transition clean verification

- Context-target、typed direct answer、trusted quote、replay、parallel draft、materialized route draft與跨領域
  notice focused batch 54／54 PASS；capability catalog與clarification inventory 5／5 PASS。
- Repo／installed conversation skill normalized-newline 4／4 MATCH，skill-creator官方`quick_validate.py`在UTF-8
  模式驗證兩份skill均為valid。
- 非clean完整root與正式fresh `scripts\mvn-safe.ps1 -Clean test`均為2,128 tests、18 skipped、0 failure；
  原先4 failures／7 errors已消除，未降低assertion、未以假duration或provider fallback掩蓋失敗。
- release仍未完成：須以final production source建立fresh runtime generation，通過官方LINE E2E與六capability
  current-contract Automatic review PASS/openCount 0，才可另啟全新24小時／至少20筆本人真實LINE baseline。
  `TR-CALENDAR-W11-MERGED`維持PENDING，第十六次baseline仍永久無效。

## 2026-08-09 context-transition runtime gate

- Official `dev-start.ps1 -SkipDispatcher`已以final production source建立generation
  `4da1b6e5eddf466596baafee6aaa58bc`；ExternalLineProbe確認main `UP/VERIFIED_DIRTY`、PostgreSQL／Redis
  healthy、LINE connected，Dispatcher維持DISARMED。
- 六項實際使用capability fresh preflight均MATCH。Laptop schema v4 Automatic review reviewedAt
  `2026-08-09T11:24:46.3818248Z`、contract `786e05082c5e226466c84d7c`，結果`PASS/openCount 0`，正式
  assertion PASS。
- release仍等待本版真人LINE代表情境與全新24小時／至少20筆aggregate-only baseline；不得沿用第十六次
  startedAt、counts、generation或receipts，`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-09 seventeenth monitoring baseline started

- 第十七次aggregate-only baseline於`2026-08-09 19:30:02.221128 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立，notBefore為`2026-08-10 19:30:02.221128 +08:00`，
  generation `4da1b6e5eddf466596baafee6aaa58bc`。起始LINE inbound／text／outbound／decision trace皆110，
  outcome為SUCCEEDED 59、CLARIFICATION 48、FALLBACK 3、FAILED 0；closure門檻為text inbound至少130。
- 起始aggregate為task 1、legacy schedule 4、Calendar plan 2、time node 3、route risk 0、active route operation
  preference 0、reminder rule／occurrence 0、intent issue 66、custom place 1、pending question 1、pending
  public-place lookup 1、active HOME 1、pending Calendar draft 0、Flyway V114。
- 快照未讀取或保存原文、external ID、UUID、地址、payload或secret；external query／product mutation為0。
  closure必須elapsed至少24小時且新增20筆均為本人真實LINE文字turn，synthetic probe／tests／benchmark不得計入。
  任何production修正會invalidate本窗口；`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-09 seventeenth monitoring baseline invalidated

- 第十七次窗口永久標為`INVALIDATED_BY_CONTEXT_CHOICE_SHORT_ANSWER_AND_LEGACY_IDENTITY_FAILURE`；其
  startedAt／notBefore、起始110、closure 130、generation與receipts均不得作release evidence。
- 真人LINE重現「繼續／新的／全部清除重來／繼續完成」的4筆`UNKNOWN/FAILED`。context-target連到exact route
  workflow但沿用舊`task` root domain與空safe label，公開提示退化為泛稱。失效時counts為inbound／text 117、
  outbound 113、trace 117；新增route draft／Calendar plan／pending row均為0。
- Production repair必須涵蓋bounded short answers、typed legacy identity recovery、restart/replay/duplicate與零business
  mutation；之後重跑focused/root/runtime/LINE/Automatic review並另建全新24小時／20筆baseline。

## 2026-08-09 route-only operation lifecycle repair

- 使用者將共用能力定義為start／resume／supply／close lifecycle，但明確要求本輪只接route，其他能力先排
  `conversation-operation-lifecycle-rollout-plan.md`並逐批審查。production registry目前只有route contributor。
- Failure-first證明自然短回答「繼續完成／新的」在context-target回傳empty；修復後以bounded exact short-answer
  policy處理。exact Calendar draft UUID可從舊`task` root domain與空safe label恢復typed route名稱；缺少或多個
  owner時fail closed。
- 「全部清除重來」只discard本scope未完成route draft、cancel目前pending並close conversation focus；已materialize
  Calendar plan與其他committed resource保留。不同actor／workspace看不到也不能關閉；terminal replay不重複mutation。
- Focused unit 24／24、route/pending/focus/RLS/actual-entry neighbor 61／61、capability/catalog/Calendar/LINE neighbor
  42／42 PASS。actual-entry涵蓋「新的→繼續→全部清除重來」、legacy identity、跨actor/workspace與zero Calendar plan。
- Repo／installed conversation skill normalized-newline 4／4 MATCH，skill-creator官方`quick_validate.py`兩份皆valid。
  尚待完整fresh clean root、runtime／官方LINE／Automatic review與全新baseline；第十七次不得復活。

## 2026-08-09 route-only lifecycle clean and runtime gate

- 正式fresh `scripts\mvn-safe.ps1 -Clean test`完成2,141 tests、18 skipped、0 failure／error，受控runner
  637.4秒。第一次runner UI於10分鐘切斷但子程序完成2,141案全綠；未冒充正式exit，第二次依前次實測
  基準加有界收尾餘裕重跑並取得exit 0。
- 測試策略已拍板改為每個testcase／suite各用自身最近成功時間、P50／P95與本次功能增量校準timeout；
  637.4秒只作相同root clean suite基準。自動歷史工具需求只登記tooling backlog，本輪未改runner。
- Official `dev-start.ps1 -SkipDispatcher`建立generation `5220661a25c44b7aac9775b3cee56cdd`；managed
  `dev-status.ps1 -ExternalLineProbe`確認main UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、
  Dispatcher DISARMED。非managed caller的access-denied status不覆蓋matching managed receipt。
- 六項fresh capability皆MATCH；正式laptop Automatic review reviewedAt
  `2026-08-09T14:16:32.4513843Z`、contract `786e05082c5e226466c84d7c`，PASS/openCount 0且assert PASS。
  尚未建立新baseline；須先完成本版真人LINE route lifecycle代表案例。

## 2026-08-10 route lifecycle actionable-resume and typed-staging repair

- Live typed metadata確認「繼續」雖找到 exact route draft，舊 public reply只命名操作而未重顯完整問題；下一輪因此落入 UNKNOWN 並覆蓋 visible question。failure-first 9 tests／2 failures後，route contributor改以 exact draft與 typed preference恢復完整 prompt／next-question。
- 使用者要求 agent 自行走完已知五步路徑後，三組 actual-entry一致揭露完整新路線在 context choice後被遺失、選「新的」仍重問內容。V115新增 nullable deferred workflow UUID pointer；route-only staging只建立 typed Calendar draft與 mode／time-role，pending不保存原文、地址或JSON，選擇前 provider／Calendar mutation為0，選新後才原子啟用。
- Focused unit 25／25 PASS（Maven 61.9秒）；三組五步 actual-entry 3／3 PASS（正式批次114.4秒，後續重跑63.2秒）；expanded lifecycle／RLS／actual-entry 73／73與capability catalog／clarification inventory精確重跑2／2 PASS。Fresh clean root 2,148 tests、0 failure／error、18 skipped、Maven 718.4秒、正式exit 0。
- Official runtime generation=`3e81c5ad1a644eceb9ca0516f7c76a50`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、官方LINE E2E connected、Dispatcher DISARMED。Laptop schema v4 Automatic review reviewedAt=`2026-08-09T23:08:12.3285502Z`、contract=`786e05082c5e226466c84d7c`，六項capability MATCH、PASS/openCount=0，獨立assertion PASS。前一代runtime／Automatic與所有舊baseline evidence持續永久失效，須建立全新24小時／20筆本人真實LINE文字baseline。

## 2026-08-10 context-first operation resume gate

- route contributor透過共用上層resume contract提供typed public topic、current progress與單一next question；公開
  renderer固定「目前正在處理 → 目前進度 → 下一步」。三組known five-step actual-entry均驗證順序、question
  exactly once、restart／legacy recovery、provider exact count與Calendar zero mutation，未加入完整句子專用branch。
- focused 25／25（79.6秒）、三組actual-entry 3／3（81.1秒）、expanded lifecycle／RLS／actual-entry 75／75
  （99.7秒）、catalog／inventory 2／2（12.4秒）PASS。Fresh clean root 2,148 tests、18 skipped、0 failure／
  error、runner execution 767.8秒、正式exit 0。
- runtime generation=`59ceae2dc1cc4f62b4c01aea61b56819`，Spring Boot `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、
  官方LINE E2E connected、Dispatcher DISARMED。Automatic reviewedAt=`2026-08-10T01:19:38.5120722Z`、六項
  MATCH、PASS/openCount=0，assertion PASS。先前runtime／Automatic與baseline全部失效，須重建24h／20筆baseline。

## 2026-08-10 standalone transport interval clarification gate

- Standalone交通規劃的operational interval由出發／抵達time role與provider duration／ETA完整決定，不再借用活動
  duration欄位，也不詢問活動結束時間。只有typed activity本體可進入`ACTIVITY_WITH_TRANSPORT`活動時長流程；
  route-shaped解析不足時只問一個交通時間問題並保持Calendar mutation為0。
- 三個常用句型failure-first後，focused 6／6（90.4秒）、route neighbor 52／52（85.8秒）、catalog／inventory
  2／2（13.4秒）PASS；fresh clean root 2,150 tests、18 skipped、0 failure／error、runner execution 822.8秒、
  正式exit 0。相同clean-root前次767.8秒，本輪增量55秒（約7.2%），在預先依歷史與功能增量選定的900秒內。
- 本worktree以未追蹤secrets alias正確綁定既有Google key；fresh provider-scoped read-only實查1條路線、1720秒／
  9624公尺，external mutation 0。此證據只證明credential與adapter可用，不改變`TDX_PRIMARY`政策。
- Runtime generation=`a46d5978e369427387ce047e305b8b46`，main `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、
  官方LINE E2E connected、Dispatcher DISARMED。READ_ONLY／SOURCE_WRITE／MAVEN／DOCKER_TEST／DEV_RUNTIME／
  LINE_E2E皆fresh MATCH；Automatic reviewedAt=`2026-08-10T03:13:18.8333414Z`、contract
  `786e05082c5e226466c84d7c`、`PASS/openCount 0`，獨立assertion PASS。
- 尚未建立本版24h／20筆本人真實LINE文字baseline；任何後續production修正仍會使上述runtime／Automatic失效，
  `TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-10 eighteenth monitoring baseline started

- 第十八次aggregate-only baseline於`2026-08-10 11:21:05.683068 +08:00`以PostgreSQL
  `REPEATABLE READ READ ONLY` transaction建立；notBefore為`2026-08-11 11:21:05.683068 +08:00`，runtime
  generation=`a46d5978e369427387ce047e305b8b46`。
- 起始LINE inbound／text 127、outbound 123、decision trace 127；outcome為SUCCEEDED 62、CLARIFICATION 58、
  FALLBACK 3、FAILED 4，Flyway V115。closure需elapsed至少24小時且text inbound至少147；新增20筆只接受本人真實
  LINE文字turn，不計synthetic probe、tests或provider query。
- 本次只讀aggregate，未讀或保存原文、external ID、UUID、地址、payload或secret，external query／product
  mutation為0。production再變更即永久失效；`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-10 eighteenth monitoring baseline invalidated

- 狀態永久改為`INVALIDATED_BY_ORPHAN_ROUTE_PENDING_SILENT_LINE_FAILURE`；startedAt／notBefore、起始
  inbound／text 127、outbound 123、trace 127、closure 147、generation與receipts均不得作release evidence。
- 三筆真人LINE turn新增3筆FAILED trace但outbound維持123。舊route question在無route draft時錯繼承active task
  workflow與safe label；新版route continuation用該identity查Calendar draft而得到`NotFoundException`，LINE controller
  僅記錄失敗、未回安全terminal訊息。Google／TDX provider均尚未進入。
- 下一個gate先以failure-first證明cross-domain owner、legacy orphan resume／new request／meta question與LINE failure
  terminal reply，再跑focused與actual-entry供真人驗證；依使用者要求不先跑clean root。

## 2026-08-10 orphan route pending lifecycle repair verification

- 根因不是route provider：未綁定route pending錯繼承active task identity，Calendar answer對該workflow執行exact get而
  拋`NotFoundException`；只清pointer還會留下舊PENDING route draft。修復以typed lifecycle owner處理，不依完整句子
  hard coding，也不把Calendar資料表邏輯放進共用層。
- `繼續`、`繼續完成`、`傳什麼？`三種常用語句均走safe orphan recovery；完整`PLAN_ROUTE_ITINERARY`可同turn
  discard唯一舊route draft並執行新要求。provider unavailable actual-entry確認exactly-one新PENDING draft、provider
  兩次invocation、calendar plan 0。
- 測試證據：failure-first 27 tests中5 failures／1 error（105.7秒）；修復後actual-entry 16/16（179.4秒）、focused
  27/27（67.5秒）、lifecycle neighbor 11/11（51.9秒）、Calendar actual-entry neighbor 19/19（85.1秒）PASS。
  測試timeout依同組上次實際執行時間與本輪重新編譯範圍調整；queue／coordination與真正Maven execution分開記錄。
- fresh DEV_RUNTIME／LINE_E2E MATCH；官方`dev-start -SkipDispatcher`及ExternalLineProbe完成。current runtime為
  `239-b32cb0d05db5-dirty`、Spring Boot UP、PostgreSQL／Redis healthy、LINE connected、Dispatcher未啟動。
  先由使用者驗證真人LINE已知路徑；驗證前不跑root clean、不建立新baseline、`TR-CALENDAR-W11-MERGED`維持PENDING。

## 2026-08-10 explicit route time and pending reactivation follow-up

- 最新真人turn選中route capability但以`ROUTE_INTERPRETATION_INCOMPLETE`錯問出發時間；資料庫證明舊task pending
  row在cancel後同transaction被重新ask，Google／TDX未進入。第十八次與其後所有舊runtime／review evidence持續失效。
- Java typed time policy保留明確「明天＋時間」，不因model UNKNOWN／錯型別遺失；pending cancel `saveAndFlush`後
  才能建立新route pointer，防止cross-domain row復活。三種常用時間格式unit、pending／transition actual-entry共
  18/18 XML PASS；精準兩條route actual-entry方法5/5 PASS（126.5秒）。
- 開發流程新增runtime source-write fence前置規範：唯讀診斷完成後、第一筆production mutation前停止同worktree
  runtime，再進Spotless／Maven；測試後才重建。此次尚未跑root clean或新baseline，先等待真人LINE結果。

## 2026-08-10 route conflict context and full Maps directions verification

- 產品owner修復generic「要改用其他時間嗎」與非量化銜接警告：公開文字現在以typed direction列出既有行程
  名稱／時間、gap、required travel與shortage；next-side或both-side只允許keep-only，不把往後移動說成安全方案。
- Google Maps公開區塊保留兩端point links，並在兩端都通過既有public endpoint gate時追加一個typed-mode
  directions URL；任一端為HOME／context／legacy／private或無效座標即不輸出完整路線座標。這是唯讀連結，
  external query與product mutation皆為0，`TDX_PRIMARY`不變。
- failure-first 18案／1 failure後，unit 23/23、Calendar actual-entry 20/20、preflight/lifecycle 9/9及standalone
  actual-entry 17/17 PASS；後兩組同時覆蓋方向錯誤回答、provider exact count、Calendar interval不變、replay與
  HOME privacy。skill repo／installed 4/4 semantic equal，官方validator 2/2 valid。
- 本增量依使用者指示先完成focused／actual-entry產品驗證，尚未執行fresh root clean或runtime／LINE gate。
  舊generation `71fa229818634462877fd313e5c06b6b`與Automatic不可沿用，baseline未啟動，release仍非READY。
- official runtime rebuild產生generation `befb5988345245d9ac5b71518d2b579a`並回exit 0，但post-return
  `dev-status -ExternalLineProbe`證明Spring Boot／DB／Redis／ngrok not running、LINE disconnected；因此該
  generation typed為INVALID，不得視為live PASS。tooling producer已由主session直接派工，Calendar dirty ownership
  保留，等待Git-verifiable修復後重跑runtime／LINE。

## 2026-08-11 Route→Place Google Maps details input

- V117 child stages為OFFER／DETAILS／CONFIRM；DETAILS接受名稱、地址、完整Google Maps URL與short link。
  allowlisted bounded redirect及typed coordinate parser由Java執行，不把URL判斷交給LLM。
- 原始Maps URL不持久化；確認前Place／Calendar mutation 0。Google Places credential未配置時，只要URL本身已有
  有效座標仍可建立typed candidate；此能力不改變`TDX_PRIMARY`或取得Google Routes／Booking mutation authority。
- Google client／Place service與三條Route→Place actual-entry共23案：首輪20 PASS、3個test contract failure；
  修正fixture／assertion後三條重跑3／3 PASS（69.2秒）。skill validator 2／2與semantic sync 4／4 PASS。
- expanded Google client／Place／Calendar actual-entry／FORCE RLS 60／60 PASS（71.4秒）；capability catalog／
  clarification inventory 2／2 PASS（8.4秒）。plain取消維持leaf-only cancel，父route與已解析時間不丟失。
- 依使用者要求未跑root clean。fresh generation=`5c61d35c203a4d59a8fa7531cbb812cc`，official dev-start與
  external LINE probe均PASS；Spring `UP/VERIFIED_DIRTY`、PostgreSQL／Redis healthy、Dispatcher DISARMED、
  LINE connected。下一步為本人真人LINE代表案例；舊runtime／Automatic／baseline evidence失效。

## 2026-08-11 route origin phrase與transient current location

- Route turn grounding現在以bounded composition接受`從／由／自`起點及`以…為起點`，再由Route→Place typed
  policy剝除出發、啟程、起程、動身、開始走與role wrapper；不把完整交通片語當Place alias。
- current-location語意與具名地點分流。`目前位置／這裡`沒有Maps evidence時只問一個本次連結問題；同句有
  Maps link時只寫目前Calendar draft的`EXPLICIT_CURRENT_TURN`座標，Place／place alias／HOME mutation為0，
  raw URL不持久化。
- failure-first與修正後證據：policy首輪50案2 failures，收緊後1 failure，最終59／59；Route→Place
  actual-entry 19／19；完整Calendar actual-entry鄰近51／51。皆使用controlled fake，external query與mutation=0。
  本增量未跑root clean，舊runtime／Automatic／baseline不得沿用。

## 2026-08-12 Route→Place stage-bound context與retain-switch repair

- 真人LINE證據確認OFFER未接受自然alias宣告與直接Maps link，且substring `建立`將完整新行程指令誤當成
  child同意，導致使用者無法接續或切換。資料庫證據為child OFFER→DETAILS、parent route仍PENDING、plan=0。
- production boundary現在以typed stage compatibility處理；OFFER接受自然宣告、地點形狀名稱／地址與Maps link，
  但exact choice不攔截完整新操作。新`CREATE_SCHEDULE`只stage non-route Calendar draft，選擇「保留目前進度並
  開始新的操作」後直接activate，不重述、不materialize Calendar。
- failure-first 7案為2 failures／2 errors。修復後7／7 PASS（81.1秒），擴充已知路徑12／12 PASS（88.4秒），
  lifecycle neighbors 98／98 PASS（37.4秒）；不相容答案保留child DETAILS與zero Place／Calendar／provider mutation。
- 舊runtime、Automatic與baseline evidence永久失效。下一步依序為完整actual-entry、fresh runtime／LINE代表案例、
  階段性clean root；root timeout以最近同scope767.8秒runner基準加本次新增案例增量計算，不含lease wait。
- 完整Calendar actual-entry首輪56案有14個舊公開文字assertion失敗，狀態與mutation無失敗；同步新版
  `已儲存／這次要怎麼處理／保留`契約後56／56 PASS（104.0秒）。catalog／clarification inventory 5／5 PASS
  （10.9秒）；repo／installed conversation skill 4／4 semantic MATCH且官方validator 2／2 valid。
- 第一輪階段性clean root 2,312 tests／18 skipped／3 failures／0 errors（676.3秒）；唯一failure family為known
  five-step三組舊公開文字assertion。更新為「繼續目前操作／保留目前進度並開始新的操作」後3／3 PASS
  （91.4秒）；須再跑全新clean root，不能把第一輪標PASS。
- 第二輪fresh clean root 2,312 tests／18 skipped／0 failures／errors PASS，Maven execution 691.7秒；更新同scope
  baseline且不含queue wait。尚待fresh runtime／LINE代表案例，`TR-CALENDAR-W11-MERGED`維持PENDING。
- Runtime generation=`bad2fa0f2f08418bb1062fedcd4380df`；official dev-start 133.3秒與ExternalLineProbe 74.8秒
  PASS，Spring Boot UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。
- Laptop Automatic reviewedAt=`2026-08-11T22:20:01.7385341Z`、contract=`786e05082c5e226466c84d7c`，六項
  MATCH、PASS/openCount=0。evidence尚未進Git，`RequireTracked`依契約fail closed；真人LINE通過並到stable
  commit gate後才可滿足tracked assertion，現不啟動baseline、不宣告READY。

## 2026-08-12 Route→Place child completion parent-resume repair

- 真人LINE的「儲存地點」未被CONFIRM child choice辨識，輸入逃到全域`ACCEPT_CONTEXT`。failure-first四案
  全部失敗；修復後三個常用儲存語句會先完成child並回寫exact parent，再依父流程是否仍有缺口決定續問或完成。
- 父流程完整矩陣驗證Place／alias／Calendar各exactly-one，輸出完整route與Maps後進入提醒附屬步驟；provider
  unavailable矩陣驗證parent PENDING、Calendar plan=0與父流程next question。SAVE replay／ONE_TIME／兩層取消
  neighbors 8／8 PASS，完整Calendar actual-entry 60／60 PASS。
- 依使用者要求本小修正尚未跑clean root。舊generation `bad2fa0f2f08418bb1062fedcd4380df`、Automatic與任何
  baseline evidence永久失效；先重建runtime並由真人LINE重測，再執行階段性clean root與release gates。
- 舊ngrok exact orphan造成第一次official start fail closed並rollback；受限managed-process入口重新驗證
  READY／`PROVEN_NOT_RUNNING`後安全收斂。第二次official start 215.5秒PASS，generation=
  `8ebb257546354822a46baae0e9fc2f21`；官方ExternalLineProbe 68.0秒PASS，主服務UP／VERIFIED_DIRTY、DB／Redis
  healthy、LINE connected、Dispatcher DISARMED。下一gate為本人真人LINE代表案例，baseline尚未啟動。

## 2026-08-12 numbered typed choice contract and Route→Place rollout

- 專案規則新增：兩個以上公開操作使用`1. 2. 3.`編號區塊、空行與保存／mutation影響；action、label、effect、
  accepted answers由同一typed catalog提供。共用renderer與lifecycle `ResumeQuestion`確保正常提問／resume／replay
  同文案。本輪只遷移Route→Place CONFIRM。
- failure-first 1／1 failure；共用renderer/catalog 11／11、Route→Place actual-entry 8／8、context-transition
  neighbors 41／41 PASS。父流程仍缺provider evidence時Calendar plan=0；父流程完整時才exactly-one materialize。
- 新production使generation `8ebb257546354822a46baae0e9fc2f21`與舊evidence失效；尚待skill sync／validator、
  fresh runtime／真人LINE與階段性clean root，`TR-CALENDAR-W11-MERGED`維持PENDING。
- 完整Calendar／catalog／inventory 65案首輪60 PASS，5案只為舊行內選項assertion；改成final-stable
  `N. 類別：影響`後精準16／16 PASS。skill repo／installed 4／4 semantic MATCH、官方validator 2／2 valid。
- 全專案早期runtime／PR gate已寫入AGENTS、decisions與test strategy：風險分層允許focused後先真人測試，
  但PR前所有scope-required gate與必要clean root仍須對exact head完整PASS。
- Early generation=`7015a7ebb075447c8f6859e011b2edd3`；official start 199.8秒與ExternalLineProbe 67.3秒
  PASS，主服務UP／VERIFIED_DIRTY、DB／Redis healthy、LINE connected、Dispatcher DISARMED。等待真人結果，
  此early deployment不滿足PR/release，baseline尚未啟動。

## 2026-08-12 Route operation completion claim gate

- 真人LINE／受控fixture確認舊版把committed Calendar `MATERIALIZED`誤當整個conversation operation完成；實際
  orphan parent仍為TIMED_POINT、provider invocation=0且Place child CONFIRM未完，公開「先前已建立」不成立。
- production新增共用completion gate並只啟用Route contributor；orphan路徑在exact revision fence下把typed origin
  接回父流程、呼叫provider、就地修復同一plan為verified interval與兩端node，再查核required child已結束後使用
  正常完整路線模板。Calendar plan維持exactly-one；可選departure reminder於完成後另問。
- failure-first 1／1 failure（105.2秒）；修正後1／1 PASS（100.8秒），typed gate 5／5、actual-entry neighbors
  7／7 PASS。skill四份repo／installed semantic 4／4 MATCH且官方validator valid。本增量未跑clean root；舊
  runtime／Automatic／baseline evidence永久失效，須先完成neighbors、fresh runtime／真人LINE再決定階段性root。
- security/persistence 19／19、conversation service 23／23、完整Calendar actual-entry 62／62 PASS。舊ngrok
  ownership/generation mismatch經official dev-stop安全收斂；fresh generation=
  `66fde7659ea9404690987c1b2bf2a2d1`，official start 155.2秒及ExternalLineProbe 82.6秒PASS，主服務
  UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。開放本人真人LINE
  代表案例；尚未跑clean root、Automatic或baseline，`TR-CALENDAR-W11-MERGED`維持PENDING。
- Early runtime後audit補出provider unavailable recovery缺口；failure-first 1／1 error（170.6秒），修正後1／1
  PASS（183.4秒），完整Calendar actual-entry 63／63（113.8秒）。audit期間source於runtime運行後被修改，依
  source-write fence主動作廢generation `66fde7659ea9404690987c1b2bf2a2d1`全部evidence，official stop雖兩次
  bounded verification逾時，受限ValidateOnly已分別證明Spring `ALREADY_STOPPED`、ngrok `PROVEN_NOT_RUNNING`。
  必須fresh start後才可真人測試；未用舊generation或LINE receipt冒充current。
- Fresh generation=`9cff4aa6dac24104b75fab6b042fa753`；official start 183.7秒與ExternalLineProbe 86.9秒
  PASS，Spring UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、Dispatcher DISARMED。本人可
  開始真人LINE代表案例；clean root／Automatic／baseline仍未執行，release非READY。
- 2026-08-12真人LINE提醒案例證明「好，前五分鐘叫我」被錯建為adaptive兩條提醒，且「哪兩個？」與
  「我問你幫我設的提醒是哪兩個」無法回指。本次production修正使generation
  `9cff4aa6dac24104b75fab6b042fa753`及其runtime／LINE evidence永久失效；baseline原本未啟動，仍不得建立。
  failure-first 23項中4 failure；修正後focused 40／40、reminder/conversation neighbors 74／74、Calendar
  actual-entry＋LINE webhook/replay 93／93 PASS。須fresh start後由本人重測明確提前量與提醒詳情追問；尚未
  跑clean root／Automatic／baseline，`TR-CALENDAR-W11-MERGED`維持PENDING。
- Fresh runtime generation=`4a6548618a474946880ee47045207679`。第一次official dev-start因舊durable
  generation receipt mismatch於164.5秒fail closed；官方stop verification雖逾時，隨後ValidateOnly證明Spring
  `ALREADY_STOPPED`與ngrok `PROVEN_NOT_RUNNING`，未直接終止程序。第二次official dev-start 262.6秒PASS；
  ExternalLineProbe 118.1秒PASS，Spring UP／VERIFIED_DIRTY、PostgreSQL／Redis healthy、LINE connected、
  Dispatcher DISARMED。已開放本人真人LINE重測；clean root／Automatic／baseline仍待後續release gate。

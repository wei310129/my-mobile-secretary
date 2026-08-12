# 變更相關性導向測試策略

## 為什麼縮小日常測試範圍

專案在 2026-08-04 有 390 份測試 Java 來源、384 個可執行 test class（2026-07-17 為 147 份；文件初版時為 57 份），其中多數 API／repository 測試會啟動
Spring、PostgreSQL/PostGIS 與 Redis Testcontainers。
這些完整整合測試適合驗收關鍵節點，但每個小修改都全跑，等待時間與輸出量會快速放大。

近期可觀察結果（2026-07-16）：

- 第一波生活對話功能：主程式與全部測試來源 `test-compile` 成功。
- 100 條能力目錄契約：1/1 通過。
- 第二波能力目錄與週期任務服務：4/4 通過。
- 第三波能力目錄與庫存 domain/service：8/8 通過。
- 第四波能力目錄、勿擾時間計算、提醒觸發與升級催促：16/16 通過；
  Maven 同時重新編譯 204 份主程式來源與 59 份測試來源成功。
- 第五波能力目錄、geofence domain 邊界與唯一規則修改／移除：15/15 通過。
- 第六波能力目錄與行程洞察：5/5 通過；Maven 重新編譯 205 份主程式與 61 份測試來源成功。
- 第七波能力目錄與待辦優先／進度洞察：4/4 通過；Maven 重新編譯 206 份主程式與 62 份測試來源成功。
- 第八波能力目錄與待辦期限／負荷洞察：6/6 通過；精準測試確認既有編譯輸出為最新，未擴跑整合測試。
- 第九波能力目錄與行程負荷／地點洞察：7/7 通過；僅跑能力契約與行程洞察單元測試。
- 第十波能力目錄與價格紀錄洞察：4/4 通過；Maven 重新編譯 207 份主程式與 63 份測試來源成功。
- 第十一波品項洞察與 LINE 實際問題修正：能力目錄、品項洞察、可行性與行程洞察 17/17 通過；
  Maven 重新編譯 208 份主程式與 64 份測試來源成功。修正衝突細節／建議、行程提醒查詢；
  同名任務編號選擇再跑能力契約 1/1 通過。
- LINE 跨使用者隱私修正：`LineOwnerGuardTest` 2/2 通過；owner 未設定時由全放行改為全阻擋。
- LINE 能力介紹誤回內部分類理由：`IntentServiceCapabilityHelpTest` 1/1 通過，改為確定性使用者說明。
- LINE 地點選錯分店：`PlaceAliasServiceTest` 1/1 通過；更具體的分店查詢不再命中舊短名稱。
- LINE 待辦建議混淆期限與地點：`TaskAdviceClassificationTest` 1/1 通過；逾期、無期限、缺地點分開回覆。
- LINE 長篇上班日常被誤判為回饋：`RecurringRoutineClarificationTest` 1/1 通過，改問實際缺少的固定時段決策。
- 對話回覆格式統一：格式器、收據、LINE client、一般提醒、天氣通知、待安排追問、行程結果追問與既有特殊回覆共 44/44 通過；
  驗證多項目條列、區塊空行、對應 emoji、LINE JSON 實際文字與重複格式化不變形。
- LINE 每日行程總覽漏掉固定上班行程：每日總覽、日期查詢攔截、巢狀行程與格式器局部測試 22/22 通過；
  `RecurringScheduleFlowTest` 4/4 通過，並以真實 PostgreSQL 驗證 V16 migration 與 `WEEKDAYS` rollover。
- LINE 運動安排忽略九點洗澡提醒：提醒時間查詢、待辦與行程衝突說明、可行性規則單元測試 16/16 通過；
  `RecurringScheduleFlowTest` 4/4 通過，確認新增 intent／planner 依賴可由完整 Spring Context 正常注入。
- 第二波 `LifestyleIntentApiTest`：測試環境找不到 Docker，Spring context 在案例執行前停止；
  因此 V14/V15 migration 與 API 整合仍列為關鍵節點待驗證，不算程式測試失敗，也不算通過。
- 本階段尚未重跑完整 `mvn test`，不得把精準測試通過誤寫成全套通過。

## 每次修改的預設選擇

1. 純 domain／service 規則：只跑對應單元測試，並讓 Maven 編譯所有受影響來源。
2. Intent type、schema 或能力目錄：加跑 `ConversationCapabilityCatalogTest`。
3. JPA entity 或 Flyway migration：在該批次收尾時跑最小相關 integration test，確認 migration 與 mapping。
4. Redis reminder 流程：只有動到 queue member、claim、排程同步或 worker 時，才跑 reminder flow 測試。Calendar reminder worker 修改時必須呼叫真實 scheduled `poll()` entry，不得只直接呼叫內部 transactional processor；同時驗證 restart catch-up、已結束 interval 不補送、exactly-once outbox、LINE stable retry key、send-time destination reauthorization 與 workspace 撤權 fail-closed。

## 開發協調工具

- Coordinator kernel、Maven/lifecycle adapter 與 handoff 一律先跑各自的 fake/disposable PowerShell gate；不得以
  shared Compose、dev volume、Flyway history 或真實 LINE endpoint 作為 failure injection fixture。
- Managed runtime lifecycle修改必須用controlled process fixtures覆蓋caller-return persistence、banner-only、
  receipt／state publication後消失、`CALLER_ACCESS_DENIED`／`UNKNOWN`／`DOWN`、exact orphan reconcile、
  interrupted stop replay與敏感資訊遮蔽。live fixture只可啟動無網路的短效process並於跨caller確認後精確停止；
  不得使用Calendar worktree、真實LINE或產品mutation作failure injection evidence。
- Managed process identity precision修改必須覆蓋native-only publication後的WMI＋native consensus、100ns／
  亞微秒來源差異、至少1微秒的真正creation-time差異與PID reuse，並重驗component、worktree、executable、
  command、generation、stale／wrong／replayed receipt都維持fail closed；canonical precision必須明載於receipt。
- Managed coordination owner修改必須用controlled operation fixtures覆蓋current-only、dead＋current、PID reuse＋
  current與genuine live competitor；另驗legacy/new typed identity、malformed/access-denied/incomplete evidence、
  read-only無writeback、正式ABANDONED reconcile、exactly-once replay及stop/restart新operation generation。
- Legacy operation／stop ordering修改必須覆蓋safe named RELEASED out-of-scope與ACTIVE dead/reuse/live矩陣、
  filename identity及path traversal deny matrix；process mutation前驗證stale durable evidence並重驗current owner，
  completion/state-write中斷須有typed recovery contract且官方replay不得再次呼叫process stop adapter。
- Testcontainers integration 維持 serial；未完成 per-test infra 隔離前，不啟用 JUnit class/method parallel。
- Dispatcher pause/drain、migration 與 protected management API 變更須跑最小 Dispatcher integration test；主應用與
  Dispatcher Maven target 不可在同一 worktree 同時寫入。
5. Controller／DTO：跑對應 API test；未改 controller 的 service 小修不重跑所有 API。
6. 外部 API client：只跑該 client 測試，不打真實服務。
7. 意圖確定性攔截（合併確認/拒絕、模糊時間守門 `VagueTimeGuard`）：跑對應單元測試
   （`DailyScheduleQueryTest`、`VagueTimeGuardTest`），改到攔截順序時加跑 `IntentApiTest`。
   注意攔截詞可能出現在任務/行程標題裡的誤攔情境要有測試。

## 必須跑完整 `mvn test` 的節點

- 一個較大功能階段準備提交或發布。
- 新增／修改多個 migration，或跨越 intent、task、schedule、reminder 三個以上模組。
- 修改共用狀態機、全域例外處理、Spring wiring 或 Testcontainers 設定。
- 精準測試出現無法由局部依賴解釋的失敗。
- 合併分支、準備 PR 或部署前。

## 目前常用命令

```powershell
# 日常第一輪：排除 integration／live，目標 30–60 秒內取得可信結果
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Fast

# 依 working tree 路徑選擇；未知、高風險或跨三模組變更會 fail-closed 升級 Full
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Relevant

# 所有 deterministic automated tests；只排除明確標記的 live evaluation
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1 -Lane Full

# 編譯主程式與全部測試來源，不執行測試
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 -DskipTests test-compile

# 生活語句能力目錄 + 週期任務規則（不需 Docker）
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 "-Dtest=ConversationCapabilityCatalogTest,TaskLifestyleServiceTest" test

# 關鍵節點完整回歸
powershell -ExecutionPolicy Bypass -File .\scripts\mvn-safe.ps1 test
```

測試範圍以「變更的依賴圖與失敗後果」決定，不用固定成功率門檻猜測品質；每次交付都要明確列出實際跑過與尚未跑的範圍。

## 對話公開邊界與 pending-state gate（2026-08-02）

- 所有 REST、LINE text/image/OCR/error/denial/replay、formatter、greeting、focus notice 與 provider/public
  reply 必須在 decoration 後、adapter send 前通過同一 typed final boundary；seed internal diagnostic、UUID、
  schema/handler、raw provider/model reason 的 failure regression。
- needs-input 每輪只允許一個 typed next question；測 partial/all-at-once/out-of-order/correction、no-repeat、
  feedback/meta/read-only preservation、parallel draft selection、quote precedence、restart、expiry 與 replay。
- pending table 必測無 raw text/JSON slot bag、actor/workspace/channel/scope fencing、FORCE RLS、owner 可見、
  peer/other workspace/system fail closed、injected `Clock` expiry 與 optimistic revision。
- unfinished-operation context transition 必測明確延續、明確新操作、模糊 target choice、兩種選擇、
  read-only/meta 插話、quote precedence、restart、replay、parallel workflow 與 multi-command。模糊輸入在
  handler/provider 前為零業務 mutation；新操作成功時 domain/focus 與舊 pointer completion 同交易；公開
  notice 至少列出保留及目前操作名稱，pending 只保存 bounded label/code/fencing 並維持 FORCE RLS。
- capability catalog 不硬編碼 enum 數量；以 `IntentCommand.Type.values()` 反向驗證每個 executable type 的
  handler、catalog、public response、clarification、pending、mutation/confirmation 與 regression contract。
- 產品持有的公共參考資料必須先查 immutable system-owned catalog，再把外部 provider 當唯讀補充；測試
  catalog 完整筆數／分類筆數、別名正規化、custom place precedence、唯一命中直接套用、同一大型地點
  多點延後到最終規劃選定、跨縣市同名只問一個縣市問題、source／selected-point／reason disclosure、
  外部查詢不被誤呼叫，以及 user custom place 零新增／零修改。接受的 system place 可進 Calendar typed
  snapshot；缺座標必須回報 route evidence 不足，不能呼叫 estimator 或猜距離。typed public-place pending
  只保存 workflow/question/revision/fencing 與 catalog key，不得保存 raw LINE text 或 generic JSON slot bag。
  catalog snapshot 必須記錄取得日期與官方來源，不得保存私人查詢 payload。
- DB-backed installation catalog 另測 `ASK_PLACE_CATALOG`／`ADOPT_PLACE_CATALOG` 的 exact、not-found、
  cross-region、multipoint、typed single-question、REST actual-entry 與 Flyway 版本唯一性；adoption 必須是
  query claim class、entity-resolved evidence、custom Place 零 mutation。bundled catalog 仍是一般自然語言
  route／knowledge canonical path，兩條 catalog surface 不得互相取得 operation authority。
- route-itinerary 必測 entity／operation 分離：明確起訖＋安排不得被 system-place 或 schedule-overview
  shortcut 攔截；模型非單一 `PLAN_ROUTE_ITINERARY` 時只問一題且 activity/plan/transport mutation 全為
  0。明確活動 mutation 成功時先精確建立一個活動、再提供可選交通規劃；拒絕零額外 mutation，接受後
  依序只問 adjustability 與 mode。明確起訖／時間的 standalone route 則必須首輪呼叫 configured provider，
  provider evidence 前不得 materialize；unavailable 只保存 typed status/mode/time-role/revision/fencing 並問
  一個 retain/discard 問題，typed retry 成功後至多建立一個 plan且不得重問已驗證 slot。quote > active route
  focus > unique transport workflow，parallel draft 不得互吃。建立前同時檢查 direct overlap、前一段與後一段
  銜接；風險回覆先列 verified route preview，再給一個建議與一個問題，不得公開 internal node label。
  provider source／Google attribution 必須公開，raw error/payload/identifier 不得公開；TDX first/last mile 不得
  靜默加入付費叫車。預設 provider 策略只有在代表性實際路線的 success、route quality、latency 與 cost
  evidence 後才能切換；另驗證 replay、RLS、fixed-cardinality metric tags 與 exactly-once mutation。
- route explicit-time必測日期＋時刻，以及`待會`／`稍後`／`等一下`／`晚點`＋數字、中文數字、半點、冒號與
  上午／下午變體；同時以裸時刻無日期、只有相對詞無時刻作negative neighbor。當時間有效但explicit origin
  未確認時，actual-entry必須保存timed start、詢問一個`route.origin-context`、provider／Calendar mutation皆0，
  且公開回覆不得再問已提供的時間。一般非route location validation不得因此放寬。
- 每個pre-provider route必要澄清（endpoint、origin、home、mode、time）都必須在pending寫入前保存可由route
  lifecycle精確解析的bounded request identity。各步驟至少驗證`取消`、一個長句取消與一個口語取消；取消先於
  slot answer，draft/pending/focus精確關閉，provider/Calendar mutation=0，duplicate delivery replay原terminal結果。
  legacy回歸另種入time-role缺失但journey-kind存在、journey-kind缺失但time-role存在，以及兩者皆缺；前兩者只有
  在exact actor/workspace/workflow lookup時可resolve，最後一種必須fail closed。
- safe-time reschedule 必測 failure-first：原風險 route 已先建立 exactly one，使用者選擇後移時第二次 provider
  request 的時間必須等於 Java 算出的缺口；provider 成功後只更新同一 fresh plan/draft與兩個 route node，
  risk exactly one 轉 `RESOLVED`。同 request replay 不得再次呼叫 provider或增加 revision；provider unavailable、
  後方風險、兩側風險、direct overlap、stale plan/node revision及 hypothetical assessment仍不安全時都要
  fail closed，維持原時間與 risk lifecycle，公開回覆不得包含 provider raw reason或 internal identifier。
- route public reply 必測掃讀卡片的標題／日期／時間軸／typed 重複規則／交通方式順序；無風險時不附
  衝突區塊，有風險時
  列出每一個相關既有行程的名稱與時間，已知地點一併列出。direct overlap 保持零新 plan 至明確確認；
  非重疊銜接風險先建立 exactly-one verified route 再只問一題。所有分支都不得公開 UUID、node key、schema、
  raw provider reason 或把 system point 說成自訂地點 mutation。
- Google Maps link 必測 explicit／trusted quote endpoint 允許、HOME／confirmed context 依產品揭露規則允許、
  legacy unknown／缺座標／無效座標禁止；連結不得暗示 Google Routes provider。TDX attribution與 Maps link
  可以同時存在，且 endpoint source 欄位必須通過 migration backfill、restart、replay、RLS 與 privacy gate。
  完整 directions URL只有兩端都通過公開gate才可輸出，必須精確映射typed driving／ride-hail／walking／
  two-wheeler／transit mode；任一端被抑制時完整網址也必須缺席，replay不得重算或增加mutation。
- route operation preference 必測一般前後緩衝、開車停車、計程車等車及可選叫車提醒；一次只問一個 typed
  分鐘值，外部活動不套用，所有時間用注入 `Clock`。停車、等車與出發提醒只能在provider成功且exactly-one
  Calendar materialization後詢問；回答附屬問題不得重新呼叫provider或重建plan。新的完整操作須自動結束未答
  附屬問題，不得進入`conversation.context-target`；provider failure、必要slot與connection risk仍須維持主要gate。
  緩衝須參與 overlap／adjacency window；提醒在等車開始
  前五分鐘且 duplicate delivery exactly-once。偏好表必測 ENABLE/FORCE RLS、跨 actor/workspace、restart。
- route journey kind 必測 raw `endAt` 與 resolved placement 交叉矩陣、single-time activity marker、restart、
  replay 與 short-answer continuation；draft 必須保存 typed kind，不得在 answer turn 只看 placement 重猜。
  adjacency 測試涵蓋 6 小時內外、14 小時缺地點、typed explicit linkage override，且遠距 unlinked node
  不得進 assessment／fingerprint。精確唯一 system point 不附 custom-place disclaimer；大型場站最終選點
  仍公開選擇與理由。
- 缺 origin 測試涵蓋 explicit/quote、same-journey、6 小時內前一地點、HOME、missing HOME、possible-away；
  HOME set/change/disable、restart、replay、parallel draft、跨 actor/workspace、owned Place FK、ENABLE/FORCE
  RLS、上午 10 點與 30 公里邊界、跨夜、返家已知／未知、海外／離島 evidence 與無 travel evidence。
  pending row 不得含 raw 地址／chat／JSON slot bag；回答前 provider 與 Calendar mutation 都為 0，回答後
  standalone exactly-one route、activity exactly-one visible activity，mutation hook 必須先於首次寫入。
- focused 後依序跑 neighbor、catalog、RLS、actual-entry，再跑全新 root regression。只有 actual LINE E2E、
  sealed holdout、public-response/latency evidence 與最後 production refresh 後的新 24h／20 text inbound
  baseline 全數通過，才可標 release READY。
- 每個啟用 conversation operation lifecycle 的 capability 必測每個 blocking step 的 start／resume／status／
  supply-input／clear；公開 status 必須依序包含 typed 主題、目前父／子步驟、已保留事實、尚待確認事實與
  唯一下一題，且 status query 的 pending revision、provider call及所有業務 mutation皆為0。父流程啟動
  子流程時，子流程完成只可回傳 typed result給父流程；父流程尚有必要步驟就恢復下一步，全部完成才由
  completion gate輸出完整成果模板。
- 兩個以上 action 或資源的公開問題／清單一律由同一個 typed catalog與共用 renderer輸出連續的
  `1.`、`2.`、`3.`項次並保留空行；同一 catalog解析數字、序數與公開標籤。資源調整／刪除必須重新取得
  actor／workspace／workflow範圍內的fresh list再解析項次，不保存或公開UUID，也不得讓舊項次指向新資源。
- lifecycle rollout一次只啟用一個經審查的 capability contributor；共用介面存在不代表相鄰 capability已
  取得取消、清除、provider或業務mutation authority。每批都要列出尚未啟用能力與下一個獨立gate。
- deterministic integration latency 不得意外呼叫真實外部 provider；MockMvc／DB／intent／public boundary／
  IN-OUT log 保持真實，provider client 以 test-only mock 隔離，HTTP client contract 由獨立測試保留。warm P95
  必須先 warm 每個代表 route，再至少量 60 samples、維持原門檻並輸出 bounded samples；少於 20 筆而使
  nearest-rank P95 等同最大值的測試不得作 release latency evidence。

### 秘書口吻、稱呼與承諾證據 gate（2026-08-04）

- `IntentResult.Action.values()` 必須動態映射到已 review 的 public template 與 claim class；新 action 無法
  分類時 catalog test fail closed，不硬編碼 enum 數量。
- public reply 可宣稱記住、開始處理、重新處理完成、資料已變更或完成後通知，僅限攜帶相符 typed
  evidence；只有 acknowledgement 或 pending repair 不得使用執行中／已完成文案。denylist 只作補強。
- final boundary 在所有 formatter／focus／image／error／replay decorator 後套用 secretary tone；一般回覆
  不自動加入 emoji。通知的專用 renderer 可保留通道需要的提示符號，但仍須通過 privacy／commitment gate。
- 稱讚與不滿各至少 10 個 controlled variants、不得連續重複；replay 重播既有 terminal wording，不因
  replay 推進 variant cursor。不滿時若沒有 fresh repair evidence，只問一個可行的釐清問題。
- 一般稱讚只能 ACK，不得推論或寫入長期回答偏好；只有明確 future-facing 指令可提交 final boundary
  確實支援的 bounded typed style。驗證 style absent／commit／read-back／reset／restart／replay、原 pending
  不被消耗、actor/workspace FORCE RLS 與零 business mutation。
- assistant self-name／user address 使用 actor/workspace-owned typed profile；multi-turn draft、variant cursor
  與 suspended pending pointer 啟用並強制 RLS，使用注入 `Clock`，不保存 raw chat 或 JSON slot bag。
  必測 direct/multi-turn/all-at-once/read-back/reset/cancel/same-value/restart/replay、跨 actor/workspace 隔離，
  以及 feedback／稱呼插話後原 business pending 精確恢復。

### 系統地點 knowledge／point browse gate（2026-08-04）

- 同一 logical place 的 knowledge、location、point list、keyword filter 與 itinerary operation 必須分型；
  knowledge 先簡短回答，不得提前說明行程選點或附加唯讀 mutation disclaimer。
- 必測 direct list、跨輪 list、unique/multiple/no-match keyword、context-free 單題澄清、新地點／quote
  override、restart、replay、actor/workspace RLS、custom-place 零 mutation 與 actual LINE terminal reply。
- Multipoint browse 只保存 catalog keys、scope、revision與 fencing；optional offer 不建立 pending question。
  低複雜度 actual-entry 至少 20 個 warm samples，model/token usage=0、P95 ≤1.5秒。

## 分層、選測與 CI gate（2026-08-04）

### 依個別歷史校準測試時間上限（2026-08-09）

- 每個 testcase 與每組 suite 都必須使用自己的最近成功耗時、P50／P95與最慢案例作為下次基準；不得把
  root總時間、固定整數或另一類測試的timeout直接套用。
- 本次功能增減須按類型調整：純unit、Spring context、Testcontainers／RLS、LINE/runtime、外部controlled fake
  分開估算；新增context啟動、container、fixture資料量、sample／replay次數與並行度都要明列。
- 已有歷史時，上限＝對應個別／suite基準＋可說明的功能增量＋有界環境波動餘裕。沒有歷史時，才引用同類
  測試的P95作provisional基準；首次完成後立即改用自身實測。
- Maven協作租約等待與測試執行必須分開計時。`queueWait`從提出租約到取得／BUSY為止；只有取得租約後，
  從Maven程序真正開始到結束的`execution`可更新該組runner基準。BUSY、caller blocked或外層工具在取得
  租約前逾時一律記為`NOT_STARTED`，不得當成測試失敗、慢測試或新的duration baseline。
- testcase與testsuite基準使用Surefire XML的實際elapsed；整組Maven runner另保存`execution`。外層tool timeout
  只可由有界queue wait＋歷史execution＋本次功能增量＋小幅傳輸餘裕組成，不得把整段wall time回寫成測試耗時。
- 逾時必須保留已完成XML時間並定位真正變慢的test／suite；不得只把全包timeout放大。任何調整都要記錄舊
  基準、新觀測、功能差異與計算理由，持續成功後收斂，不得單向膨脹。
- 2026-08-09 route lifecycle clean root實測為2,141 tests／18 skipped／637.4秒，只能作相同root clean suite
  的下一輪參考；focused、actual-entry與RLS仍各自使用Surefire testcase／testsuite時間。
- 2026-08-10 context-first resume最後production source clean root為2,148 tests／18 skipped／767.8秒、正式exit 0；
  這是下一輪相同root clean scope的新runner execution基準。focused 25-test、known five-step 3-test與expanded
  75-test仍分別使用79.6、81.1、99.7秒，不得以767.8秒取代。
- 共用operation cancellation必測至少三個常用語句、完整Intent actual-entry、request replay、typed draft discarded、
  pending canceled、既有committed resource exact count不變與provider zero re-invocation；另以帶日期／時間的明確
  committed-resource取消作neighbor，證明不會被current-operation控制語句攔截。generic unknown question必須有
  persistence actual-entry fence，證明不覆蓋原capability pending owner。

### 測試指標資料契約（2026-08-10）

所有本機、CI與release evidence使用同一組定義：

- `queuedAt`：runner提出協作租約的時間。
- `leaseAcquiredAt`：取得全部必要租約的時間；未取得時後續時間皆為空。
- `testStartedAt`：Maven／對應test process實際開始時間，不以shell建立時間代替。
- `testFinishedAt`：test process終止並取得正式exit code的時間。
- `queueWaitMs = leaseAcquiredAt - queuedAt`；只衡量資源排隊，不是測試效能。
- `executionMs = testFinishedAt - testStartedAt`；衡量該runner scope的編譯、context／container啟動、測試與
  正常收尾。只有正式exit後才有值。
- `testcaseMs`與`testsuiteMs`取自本次正式Surefire XML；不得用console推測、半成品XML或runner wall time替代。
- `P50`使用排序後50百分位，`P95`使用nearest-rank 95百分位，`max`為同scope最慢值；sample count、cold／warm、
  Spring context、Testcontainers、runtime／provider mode必須一起記錄，不能跨模式比較。

Outcome固定為：

- `PASS`：正式exit 0，所有適用assertion通過；skip數另列，不能用skip冒充pass coverage。
- `FAIL`：測試assertion未通過，且有對應testcase／suite evidence。
- `ERROR`：編譯、context、container、fixture或runner execution已開始後異常終止。
- `SKIPPED`：由既有明確條件跳過；新增或增加skip必須說明，不得用來關閉失敗。
- `NOT_STARTED`：租約BUSY、caller blocked、preflight失敗，或在`testStartedAt`前終止。此狀態不得更新duration
  baseline，也不得算產品測試失敗。
- `INCOMPLETE`：測試已開始、外層工具失聯或逾時，但尚未取得正式runner exit；可以保留已完成XML作診斷，
  不得宣稱整組PASS或用外層wall time更新baseline。

Baseline選擇固定為：同一testcase／suite／runner scope、相同cold／warm與基礎設施模式、最近一次正式成功值；
有足夠歷史時同時保存P50／P95／max。功能增量必須分別說明新增case／sample、Spring context、container、fixture、
replay、外部controlled fake與格式／編譯成本。timeout公式為：

`execution timeout = matching historical execution or P95 + explained feature delta + bounded environment margin`

`outer tool timeout = bounded queue timeout + execution timeout + bounded transport/finalization margin`

任何新基準都要記錄scope、舊值、新值、差異、原因、outcome與證據來源；不得因一次逾時單向放大。

測試分批順序只用來縮短time-to-first-failure，不建立test state相依。每個testcase／suite在clean process、任意合法
順序與單獨執行時都必須得到相同結果；若不同即視為shared state、Clock、transaction、context cache或fixture隔離
缺陷。增量開發先跑focused→neighbor→catalog／inventory；相同production／test／config／environment fingerprint下，
已通過的精確案例可不在下一個增量批次重複，但final fresh clean root仍完整重跑全部automated tests。clean root內部
不要求複製增量批次順序，因最相關案例已先驗證；若未來要排序，只能優化失敗回饋速度，不得影響correctness或選測。

2026-08-10 standalone transport interval最後production source：focused 6／6為90.4秒、route neighbor 52／52為
85.8秒、catalog／inventory 2／2為13.4秒；fresh clean root為2,150 tests／18 skipped／822.8秒、正式exit 0。
相同clean-root前次767.8秒，差異+55秒（約7.2%）；本輪依新增案例與格式／編譯增量預先使用900秒execution上限，
結果可接受但不覆蓋個別focused／neighbor的獨立基準。

### 測試異常改善流程（2026-08-10）

1. 先依時間戳分類為租約排隊、process啟動、編譯／格式、Spring／container、testcase、suite或收尾問題。
2. `NOT_STARTED`只處理ownership、租約、caller或環境，不修改產品、不放寬測試timeout。
3. `FAIL`先保留failure-first testcase與exact assertion；不得降低assertion、改名、skip或只延長timeout求綠。
4. `ERROR`依owner修復產品、fixture、環境或tooling；產品session不得順手改啟停／runner，重複工具問題登記
   `docs/tooling-backlog.md`交由tooling lane。
5. `INCOMPLETE`讀取已完成Surefire XML定位最後完成的testcase／suite，確認process與租約disposition；不得把
   外層逾時秒數視為execution。
6. 修復後先跑最小同scope回歸，再跑neighbor／RLS／actual-entry／capability gate，最後依風險跑fresh clean root。
7. 每次重跑使用同一指標契約，驗證correctness、exact／zero mutation、replay、privacy與時間；正式exit前不關閉。
8. 只有正式成功結果可更新baseline；若新值明顯增加，必須解釋功能增量或繼續定位，不能只接受變慢。

## 全專案早期runtime與PR交付gate（2026-08-12）

- agent依變更範圍、共享consumer、mutation、security／RLS、schema與最近同類失敗證據決定測試深度；小修不固定
  先跑clean root，跨核心／schema／security、高污染疑慮或正式release才要求相應clean root。
- failure-first、focused與必要neighbor通過後，可先用managed lifecycle重建開發runtime讓使用者提早真人測試；
  此時明示尚未完成PR gate。runtime運行期間不得跑需source-write／Maven claim的測試，補測前先managed stop。
- 真人測試與較廣regression可分階段，真人結果不取代自動測試。任何commit／push／非Draft PR前，必須完成本次
  scope要求的focused、neighbor、security／RLS、actual-entry、catalog／inventory、必要clean root、sealed／
  runtime／Automatic等正式gate；未跑、失敗或不屬exact head均不得交付。PR head改變後重跑受影響required checks。

### 固定分層

- `fast`：不啟動完整 Spring／Testcontainers 的純 Java domain、application policy、formatter 與契約測試。
- `integration`：完整 Spring wiring、MockMvc、PostgreSQL/PostGIS、Redis、Flyway、RLS、outbox 或 concurrency 測試。
- `live`：需要真實 model／外部服務或人工授權的 opt-in evaluation；一般 automated regression 永遠排除。
- `migration`、`rls`、`conversation`、`latency` 是風險 traits，不取代 primary lane。
- `IntegrationTestBase` 統一帶 `integration` tag；獨立 Testcontainers migration test 同時帶
  `integration`／`migration`。新增高成本測試不得依檔名猜分類。

`scripts/test-inventory.ps1` 以 test source、Spring／Testcontainers 種子與 class inheritance 建立 inventory。
未分類測試仍屬 automated；report aggregator 要求 381 個 automated test class 在 Fast＋三個 Integration
shard 中恰好各出現一次，漏測、重複或額外 suite 都失敗。

### Relevant fail-closed 規則

- 一般單模組 Java 變更先跑 Fast，再追加該模組的 integration classes。
- intent／conversation 與 booking／execution／payment 使用已知相鄰模組集合。
- `pom.xml`、Flyway、共用 API／shared、整合測試基底、test tooling、未知路徑或三個以上模組直接 Full。
- `Relevant` 只讀 `git status --porcelain`，不依賴未受控的 Git diff；呼叫端也可用 `-ChangedPaths` 明示範圍。
- 精準測試不取代 PR、合併及部署前完整 gate；執行結果必須回報實際 lane 與升級理由。

### GitHub Actions

- `.github/workflows/test-gates.yml` 使用 Java 21 Ubuntu ephemeral runners 與 Maven dependency cache。
- PR 先執行 required `Merge policy`：只接受已登錄 branch ownership，驗證 changed-path allowlist、敏感
  刪除／改名、Flyway immutability／版本唯一性、handoff 狀態／SHA ancestry 與一次性 trigger 消耗一致性。
  未登錄 branch、跨 lane 寫入或 durable state 矛盾一律 fail closed。
- Agent 實際合併只能經 `scripts/merge-pr.ps1`，以使用者當輪授權的 PR／head SHA 重新查核 GitHub；
  head 改變、draft、非 CLEAN、required check 不綠、branch protection 不 strict 或管理員可 bypass 都拒絕。
- Commit 前可用 `scripts/merge-policy.ps1 -UseWorkingTreeChanges` 依 `git status --porcelain` 驗證本機範圍；
  CI 不信任本機摘要，仍從 GitHub Pull Files API 重新取得完整 changed-file 清單。
- `pom.xml`、test／CI tooling、migration、application config 或 integration base 等高風險路徑會在 PR
  合併前加跑 one-JVM `PR risk serial regression`；`Merge policy` 只有在必要 serial 成功後才成功。
- PR required checks 應設定為 `Merge policy`、`Fast tests`、三個 `Integration shard` 與
  `Automated regression complete`；integration job 之間並行，單一 job 內保持 serial。
- `main` push 額外執行 `Main serial regression`，偵測 context cache、順序或跨 suite 污染；部署只能使用
  同一 SHA 已通過 PR 聚合與 main serial regression 的版本。
- CI 不快取 `target`、資料庫或 container state，不使用 Testcontainers experimental reuse；失敗測試不得
  靜默 retry、永久 quarantine 或以新增 skip 維持綠燈。
- 第一階段不設 coverage 百分比硬閘；保留 Surefire XML 14 天，先以每 job 時間、slow suites、skip 與
  shard imbalance 建立趨勢。完成穩定觀察後再決定 coverage 趨勢產物，不把低價值百分比當品質替代品。

### 效能目標與逐批重整

- 一般純 domain／service 變更：Fast cold P90 ≤ 60 秒、warm P90 ≤ 30 秒。
- Relevant 必須先在 60 秒內交付 Fast 結果；需要容器的 focused integration 可繼續執行。
- PR 三個 integration shards：初始目標 median ≤ 6 分鐘、P90 ≤ 8 分鐘；以 GitHub 實測校正 shard，
  不以刪案例達標。
- 後續一次只重整一個 subsystem：抽出純 Java policy、縮窄 controller protocol test、保留每個 domain
  至少一條真實 entry path；repository、Flyway、RLS、transaction、async／outbox 仍使用真實基礎設施。
- fixture reset 僅在證明 transaction rollback 等價後才縮小；HTTP、async、commit visibility、RLS
  transaction-local 與 migration 案例維持必要的真實 reset。

### Context 壓縮提醒點

- **CI 基礎完成**：inventory、Fast／Relevant／Full、三 shards、aggregator 與 main serial gate 全部驗證後，
  主動提醒適合壓縮；續作摘要保留目前階段、已拍板決策、不變量、修改檔案、雙機基準、測試結果、
  未完成工作、下一步與風險。
- **每個 subsystem 重整出口**：該批 Fast／Relevant／PR Full／main serial 全綠且行為等價已記錄後才提醒；
  migration 中途、測試失敗未定位或仍依賴大量未摘要上下文時不得提醒。
- **20 個 PR 穩定觀察完成**：保留最終 SHA、P50／P90、漏選、flaky、shard imbalance、例外與後續治理規則。

## 現況快照（2026-08-04）

- 主應用：390 份測試 Java 來源、384 個可執行 test class；inventory 為 Fast 257、Integration 124、
  Live 3，automated 合計 381。桌電 2026-08-02 既有 Surefire 報告為 1,618 tests、0 failure、0 error、
  16 skipped，test class 累計 311.4 秒；歷史完整 Maven wall time 約 319.6–836.8 秒。
- `internal/ai-dispatcher`（獨立 Maven 專案）：72 份 Java 來源、13 份測試類別；測試指令
  `.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test`，與主應用測試互不影響。
- 上方逐波觀察紀錄保留為歷史 log；新的觀察請依日期續記，不回改舊紀錄。

## 對話 parent-child lifecycle gate（2026-08-11）

- 每一條新 parent-child edge 必須先證明原路徑無法進入／恢復子流程，再驗證至少三個常用狀態詢問語句；
  狀態查詢前後的 draft revision、pending question與 committed mutation count必須不變。
- child 至少覆蓋「儲存並繼續／只用這次／取消 leaf／取消整棵未完成樹」、restart、replay、parallel draft、
  actor/workspace RLS、provider unavailable及 exact parent revision。provider evidence前 Place與Calendar皆為0；
  成功後只允許契約指定的 exactly-one mutation。
- 公開回覆 assertion 必須包含可辨認的父主題、子階段、保留資訊、缺口與一個下一步，並拒絕 UUID、schema、
  handler、raw provider reason。新增 capability仍須跑 capability catalog與clarification inventory。
- runner wall time只從實際取得協作租約並啟動 Maven後計算到 Maven結束；等待租約與preflight另列。timeout以同一
  selector最近一次runner實測為基準，再依新增案例／容器／provider fake變化調整，不以任意固定秒數猜測。
- Route→Place DETAILS輸入矩陣至少涵蓋名稱、地址、完整Google Maps URL、短連結、超過300字但不超過2048字的
  合法URL、非Google URL、hostile redirect與provider unavailable。所有連結測試使用controlled fake，不呼叫
  live provider；確認前assert Place／Calendar mutation 0，且pending row、公開回覆與diagnostic均不含原始URL。
- Route origin語句矩陣不得只列reported sentence；至少組合`從／由／自／以`、`出發／啟程／起程／動身／開始走`、
  引號、空白、口語方位與`出發地／起點`欄位式語法。neighbor必須保留包含交通字樣的真實名稱，例如
  `重新出發咖啡／出發工作室／公司出發口／起點咖啡`。
- `我目前的位置／現在的位置／這裡／這邊／此處`必須驗證為一次性origin：無Maps evidence時只問一個連結問題；
  有Maps evidence時不得出現建立或儲存自訂地點選項。兩者都assert Place、place alias、HOME preference與
  provider evidence前Calendar mutation為0，pending row與公開回覆不得含raw URL。
- Route→Place OFFER至少以自然宣告、直接地點名稱／地址、Google Maps連結三種常用輸入走完整actual-entry；
  `建立地點`只能由bounded exact choice接受，不能以substring攔截新的行程／活動指令。新操作必須先stage
  typed draft且zero Calendar materialization，再詢問繼續或「保留目前進度並開始新的操作」；選擇保留後
  直接activate staged draft，不要求重述原句。
- child stage不相容輸入必測至少新操作、狀態詢問與明確異議。回覆要包含父主題、目前子階段、資料未修改、
  原本可接受輸入與保留切換方式；draft step／revision、pending、Place、Calendar與provider count保持不變。
- child terminal success必須成對測試「父流程仍有缺口」與「父流程已完整」。前者assert child completed、typed值
  回寫exact parent、parent仍PENDING、next question屬於parent且Calendar mutation為0；後者才允許exactly-one
  parent materialization，並須輸出完整父流程結果。CONFIRM至少以三個常用儲存語句actual-entry測試，且刻意讓
  interpreter產生可能競爭的全域`ACCEPT_CONTEXT`，證明child stage優先接管、不會提前確認父草稿。
- 另以歷史錯誤或受控fixture建立「child仍CONFIRM、exact parent已MATERIALIZED且revision恰為+1」案例；
  `MATERIALIZED`不得直接當成公開完成證據。回答顯示序號與文字答案後，必須完成leaf、契約指定的Place mutation、
  provider invocation及同一plan的verified interval／exactly-two endpoints修復，Calendar plan保持exactly-one；只有
  共用completion gate驗證draft／plan／nodes／provider evidence／required child全數完成，才輸出正常完整路線模板。
  可選出發提醒可在此後保留唯一pending；status不同、revision跳號或parent不符仍須fail closed並保持zero新mutation。
- 任何兩個以上的公開選項必須在final LINE文字assert為`1. 2. 3.`連續編號、選項間空行、每項包含操作名稱與
  保存／mutation影響，並assert不含`A、B，還是C？`行內選項。renderer unit要驗證順序與拒絕少於兩項；
  capability catalog test要從同一資料來源驗證公開label、顯示序號、全形序號、常用序號說法與文字答案解析，
  並拒絕超出當下choice set的序號。正常提問、lifecycle resume、restart與replay必須輸出並解析同一choice set；
  此驗收契約全專案適用，但新增共用primitive不代表一次改寫未納入本輪審查的其他capability。

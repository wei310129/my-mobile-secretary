# 變更相關性導向測試策略

## 為什麼縮小日常測試範圍

專案目前有 147 份測試來源（2026-07-17 計數；文件初版時為 57 份），其中多數 API／repository 測試會啟動
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
4. Redis reminder 流程：只有動到 queue member、claim、排程同步或 worker 時，才跑 reminder flow 測試。
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
# 編譯主程式與全部測試來源，不執行測試
.\mvnw.cmd -DskipTests test-compile

# 生活語句能力目錄 + 週期任務規則（不需 Docker）
.\mvnw.cmd "-Dtest=ConversationCapabilityCatalogTest,TaskLifestyleServiceTest" test

# 關鍵節點完整回歸
.\mvnw.cmd test
```

測試範圍以「變更的依賴圖與失敗後果」決定，不用固定成功率門檻猜測品質；每次交付都要明確列出實際跑過與尚未跑的範圍。

## 現況快照（2026-07-17）

- 主應用：356 份主程式來源、147 份測試來源（其中 `*Test.java` 143 份）。
- `internal/ai-dispatcher`（獨立 Maven 專案）：72 份 Java 來源、13 份測試類別；測試指令
  `.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test`，與主應用測試互不影響。
- 上方逐波觀察紀錄保留為歷史 log；新的觀察請依日期續記，不回改舊紀錄。

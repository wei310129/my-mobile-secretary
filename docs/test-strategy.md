# 變更相關性導向測試策略

## 為什麼縮小日常測試範圍

專案目前有 57 份測試來源，其中多數 API／repository 測試會啟動 Spring、PostgreSQL/PostGIS 與 Redis Testcontainers。
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

# 行程與生活助理情境目錄（1–1000）

## 資料範圍

- 1–50：真實模型測試與人工複核，見 `schedule-conversation-evaluation-2026-07-17.md`。
- 51–100：真實模型測試與人工複核，見 `schedule-conversation-evaluation-051-100.md`。
- 101–150：40–54 字真實模型測試，原始報告為
  `target/schedule-intent-live-evaluation-101-150.md`，可由
  `ScheduleIntentProgressiveLiveEvaluationTest` 重跑。
- 151–1000：每 50 筆一份的發想與驗收資料，見下列批次檔案。

## 發想批次

- `schedule-scenario-ideas-151-200.md`
- `schedule-scenario-ideas-201-250.md`
- `schedule-scenario-ideas-251-300.md`
- `schedule-scenario-ideas-301-350.md`
- `schedule-scenario-ideas-351-400.md`
- `schedule-scenario-ideas-401-450.md`
- `schedule-scenario-ideas-451-500.md`
- `schedule-scenario-ideas-501-550.md`
- `schedule-scenario-ideas-551-600.md`
- `schedule-scenario-ideas-601-650.md`
- `schedule-scenario-ideas-651-700.md`
- `schedule-scenario-ideas-701-750.md`
- `schedule-scenario-ideas-751-800.md`
- `schedule-scenario-ideas-801-850.md`
- `schedule-scenario-ideas-851-900.md`
- `schedule-scenario-ideas-901-950.md`
- `schedule-scenario-ideas-951-1000.md`

每筆包含：使用者原始輸入、完整原意、應做到事項、預期主行為與開發狀態。

持續開發的 checkpoint、對話問題優先規則與下一項功能，見
`schedule-development-progress.md`。

## 機械驗證結果

- 151–1000 共 850 筆，編號連續且無重複。
- 每個批次檔恰好 50 筆。
- 201–1000 每筆至少 50 字。
- 全部輸入皆不超過 300 字；目前最長 289 字。
- 資料包含口語、無標點、錯字、同音誤植、中英混用、多次修正、否定、
  多日期、多角色、多來源、條件、權限、隱私、回滾與部分成功。

## 真實模型基準

| 批次 | 輸入長度 | 第一指令型別／數量命中 | 人工複核 |
|---|---:|---:|---|
| 1–50 | 既有對話延伸 | 40/50 | 正確 38、部分正確 4、失敗 8 |
| 51–100 | 6–39 字 | 35/50 | 正確 32、部分正確 9、失敗 9 |
| 101–150 | 40–54 字 | 38/50 | 尚待逐欄人工複核；已保留完整原始輸出 |

型別命中不代表日期、欄位、執行順序或安全性正確；後續自動評估必須加入
欄位級與副作用級斷言。

## 已開發並有回歸測試

1. 送孩子上課但沒有指定接回安排時，主動詢問誰送、誰接、接回時間與地點。
2. 「可能、未定、待確認、還不知道誰接」不視為已指定接送人。
3. 純接送句直接由服務層安全攔截；多意圖句會移除不安全的孩子行程建立，
   保留可處理的獨立事項，再附接送澄清。
4. 明確指定老婆、外婆、校車或自行返家時，不假設由使用者接。
5. 「老師通知」可觸發家庭通知確認草稿；中文「十點到校、十二點結束」可解析，
   確認前不直接建立一般行程。

相關程式：

- `src/main/java/com/aproject/aidriven/mymobilesecretary/intent/application/IntentService.java`
- `src/main/java/com/aproject/aidriven/mymobilesecretary/intent/application/AnthropicIntentInterpreter.java`
- `src/main/java/com/aproject/aidriven/mymobilesecretary/family/application/FamilyMessageService.java`

回歸測試：

- `DailyScheduleQueryTest`
- `FamilyMessageServiceTest`

## 已知但尚未開發的高優先缺口

1. 星期文字與實際日期的一致性驗證。
2. 條件式行程、備案與只保留一個最終分支。
3. 「先檢查衝突再建立」的強制執行順序與原子批次操作。
4. 農曆、政府假日、每月第 N 個星期與每隔週等進階日期規則。
5. 家庭成員歸屬、授權、候選與承諾的分層狀態。
6. 草稿、部分成功、欄位級來源、置信度與不確定值模型。
7. 外部付款、訂位、預約、購票、傳訊的統一確認與冪等安全閘。
8. 多資源、跨時區、跨地點、交通緩衝與多人共同空檔排程。
9. 敏感資料分級共享、通知去識別與稽核可解釋性。
10. 跨系統失敗的回滾、補償、精確重試及收件人級去重。

上述缺口在 151–1000 的每筆情境中均已標註預期結果，可直接交給後續 Codex
逐批轉成測試與實作。

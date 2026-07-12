package com.aproject.aidriven.mymobilesecretary.intent.application;

/**
 * LLM 解析出的結構化意圖(AI 五層第 1 層的輸出)。
 *
 * 時間欄位用 ISO-8601 含時區字串(如 "2026-07-13T11:00:00+08:00"),
 * 由 IntentService 解析驗證——LLM 輸出一律不直接信任,schema 驗證後才執行。
 *
 * @param type      意圖種類
 * @param title     任務/行程標題(去掉時間地點後的動作本體)
 * @param dueAt     任務期限(CREATE_TASK 用,可空)
 * @param startAt   行程開始(CREATE_SCHEDULE 必填)
 * @param endAt     行程結束(CREATE_SCHEDULE 必填;使用者沒說就依常識估)
 * @param placeName 使用者明講的地點名稱(沒講就空;不要猜)
 * @param priority  LOW/NORMAL/HIGH(聽得出急迫才填,預設 NORMAL)
 * @param reason    UNKNOWN 時說明缺什麼資訊,回問使用者用
 */
public record IntentCommand(
        Type type,
        String title,
        String dueAt,
        String startAt,
        String endAt,
        String placeName,
        String priority,
        String reason
) {

    public enum Type {
        /** 建任務(待辦,無固定時段;可有期限)。 */
        CREATE_TASK,
        /** 建行程(有明確開始/結束時段的承諾)。 */
        CREATE_SCHEDULE,
        /** 聽不懂或資訊不足,需要回問。 */
        UNKNOWN
    }
}

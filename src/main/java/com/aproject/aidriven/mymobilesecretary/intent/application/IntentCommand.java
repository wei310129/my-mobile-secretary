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
 * @param startAt   行程開始(CREATE_SCHEDULE/RESCHEDULE_SCHEDULE 必填)
 * @param endAt     行程結束(CREATE_SCHEDULE 必填;RESCHEDULE_SCHEDULE 可空,空時沿用原時長)
 * @param placeName      使用者明講的地點名稱(沒講就空;不要猜)
 * @param priority       LOW/NORMAL/HIGH(聽得出急迫才填,預設 NORMAL)
 * @param reason         UNKNOWN 時說明缺什麼資訊,回問使用者用
 * @param onTime         RECORD_OUTCOME:是否準時(說「準時/順利結束」= true)
 * @param overrunMinutes RECORD_OUTCOME:超時分鐘數(「晚了半小時」= 30)
 * @param outcomeReason  RECORD_OUTCOME:MEETING_OVERRUN/TRAFFIC_INCIDENT/RUSH_HOUR/OTHER(聽得出才填)
 * @param windowHours    SUGGEST_NEARBY:使用者明講的時間長度(小時);沒講就空,不要猜
 * @param recurring      CREATE_SCHEDULE/SET_SCHEDULE_RECURRING:每週固定行程為 true
 */
public record IntentCommand(
        Type type,
        String title,
        String dueAt,
        String startAt,
        String endAt,
        String placeName,
        String priority,
        String reason,
        Boolean onTime,
        Integer overrunMinutes,
        String outcomeReason,
        Integer windowHours,
        Boolean recurring,
        IntentOptions options
) {

    /** 舊的 13 欄 command 仍可用;新增能力才需要 options。 */
    public IntentCommand(Type type, String title, String dueAt, String startAt, String endAt,
                         String placeName, String priority, String reason, Boolean onTime,
                         Integer overrunMinutes, String outcomeReason, Integer windowHours,
                         Boolean recurring) {
        this(type, title, dueAt, startAt, endAt, placeName, priority, reason, onTime,
                overrunMinutes, outcomeReason, windowHours, recurring, null);
    }

    public IntentOptions safeOptions() {
        return options == null ? IntentOptions.empty() : options;
    }

    public enum Type {
        ADD_SCHEDULE_REMINDER,
        SUGGEST_FREE_SLOT,
        CREATE_RELATIVE_SCHEDULE,
        LIST_AGENDA,
        ASK_TASK_INFO,
        ASK_AVAILABILITY,
        LIST_RECENT,
        SUGGEST_ROUTE_TASKS,
        SET_PLACE_ALIAS,
        ADD_SHOPPING_ITEMS,
        REMOVE_SHOPPING_ITEM,
        LIST_SHOPPING_ITEMS,
        SET_INVENTORY,
        ASK_PRICE_COMPARISON,
        ASK_WEATHER,
        CREATE_WEATHER_REMINDER,
        ASK_TRAVEL_TIME,
        ASK_DEPARTURE_TIME,
        CREATE_TRAFFIC_WATCH,
        CHECK_FEASIBILITY,
        SET_PLANNING_BUFFER,
        ACCEPT_CONTEXT,
        SHIFT_CONTEXT_LATER,
        CANCEL_CONTEXT,
        SET_CONTEXT_PLACE,
        COPY_CONTEXT,
        SOCIAL,
        /** 建任務(待辦,無固定時段;可有期限)。 */
        CREATE_TASK,
        /** 建行程(有明確開始/結束時段的承諾)。 */
        CREATE_SCHEDULE,
        /** 回報任務做完了(「牛奶買到了」);title 放任務關鍵字,配對由 Java 規則做。 */
        COMPLETE_TASK,
        /** 取消待辦(「取消買排骨」);title 放關鍵字。 */
        CANCEL_TASK,
        /** 一次取消全部待辦(「全部待辦都取消」)。 */
        CANCEL_ALL_TASKS,
        /** 改待辦期限(「拿包裹改成今天11點」);title 關鍵字 + dueAt 新期限。 */
        RESCHEDULE_TASK,
        /** 取消既有行程(「明天的會議取消」);title 放行程關鍵字,由 Java 規則找唯一行程。 */
        CANCEL_SCHEDULE,
        /** 把行程設為/取消每週固定(「送女兒上課是每週固定的」);title 關鍵字 + recurring。 */
        SET_SCHEDULE_RECURRING,
        /** 問某行程的細節(「送女兒上課是固定行程嗎?」「會議是幾點?」);title 關鍵字。 */
        ASK_SCHEDULE_INFO,
        /** 查某品項的價格明細(「列出買奶粉的明細」);title 放品項關鍵字。 */
        ASK_PRICE_HISTORY,
        /** 改既有行程時間(「週會改到兩點」);title + startAt,未指定 endAt 時沿用原時長。 */
        RESCHEDULE_SCHEDULE,
        /** 問已知地點的資訊(「全聯是指哪一間?」);placeName 放地點名。 */
        ASK_PLACE,
        /** 建立地點(「建立地點:蝦皮店到店中興二店」);placeName 放地點名,詳細資訊由 Google 補全。 */
        CREATE_PLACE,
        /** 把待辦綁到地點(「拿包裹是要到蝦皮店到店」);title 任務關鍵字 + placeName 地點名。 */
        BIND_TASK_PLACE,
        /** 問某待辦要去哪裡做(「我要去哪取蝦皮?」);title 任務關鍵字。 */
        ASK_TASK_PLACE,
        /** 對系統本身的抱怨/建議/回饋(「你是不是重複建立了」);記錄給開發者。 */
        FEEDBACK,
        /** 查詢未完成的待辦(「還有什麼要做」)。 */
        LIST_TASKS,
        /** 查詢接下來的行程(「今天有什麼行程」)。 */
        LIST_SCHEDULES,
        /** 問待會(未來 3 小時)可以順路做什麼(「待會有什麼可以順便做」)。 */
        SUGGEST_NEARBY,
        /** 回報行程實際結果(準時嗎/超時多久/原因)。 */
        RECORD_OUTCOME,
        /** 聽不懂或資訊不足,需要回問。 */
        UNKNOWN
    }
}

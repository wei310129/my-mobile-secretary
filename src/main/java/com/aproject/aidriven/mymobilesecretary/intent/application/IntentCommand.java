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
        EXPLAIN_LAST_FAILURE,
        ADD_SCHEDULE_REMINDER,
        SUGGEST_FREE_SLOT,
        CREATE_RELATIVE_SCHEDULE,
        LIST_AGENDA,
        ASK_TASK_INFO,
        ASK_AVAILABILITY,
        LIST_SCHEDULES_ON_DATE,
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
        UPDATE_TASK,
        PAUSE_RECURRING_TASK,
        RESUME_RECURRING_TASK,
        SKIP_RECURRING_OCCURRENCE,
        LIST_COMPLETED_TASKS,
        MARK_SHOPPING_PURCHASED,
        CLEAR_SHOPPING_LIST,
        LIST_SHOPPING_BY_PLACE,
        AGENDA_SUMMARY,
        RESIZE_SCHEDULE,
        ADJUST_INVENTORY,
        LIST_INVENTORY,
        ASK_ITEM_PLACES,
        BIND_ITEM_PLACE,
        LIST_ITEMS_BY_PLACE,
        GROUP_SHOPPING_BY_PLACE,
        RESTOCK_LOW_INVENTORY,
        SET_QUIET_HOURS,
        CLEAR_QUIET_HOURS,
        MUTE_REMINDERS,
        RESUME_REMINDERS,
        ASK_REMINDER_PREFERENCES,
        LIST_LOCATION_TASKS,
        ASK_PLACE_TASKS,
        ASK_TASK_GEOFENCE,
        UPDATE_TASK_GEOFENCE,
        REMOVE_TASK_PLACE,
        ASK_NEXT_SCHEDULE,
        ASK_SCHEDULE_GAP,
        GROUP_SCHEDULES_BY_DAY,
        CHECK_SCHEDULE_CONFLICTS,
        SUGGEST_NEXT_TASK,
        GROUP_TASKS_BY_CATEGORY,
        ASK_TASK_PROGRESS,
        GROUP_TASKS_BY_DUE,
        ASK_TASK_LOAD,
        ASK_BUSY_TASK_DAY,
        ASK_BUSY_SCHEDULE_DAY,
        ASK_LONGEST_SCHEDULE,
        GROUP_SCHEDULES_BY_PLACE,
        ASK_ACTIVITY_COUNT,
        ASK_LAST_ACTIVITY,
        /** 開始蒐集旅行／出遊規劃資料；只回問與分類，不直接建立任何資料。 */
        PLAN_TRIP,
        /** 依目前旅行上下文與個人偏好產生行李清單草案。 */
        PLAN_PACKING_LIST,
        /** 列出已明確保存的長期行李偏好。 */
        LIST_PACKING_PREFERENCES,
        /** 設定長期行李偏好；title=品項，options.filter=ALWAYS/NEVER/CLEAR。 */
        SET_PACKING_PREFERENCE,
        /** 顯示最近一次由圖片擷取、尚待確認的旅行行程表。 */
        SHOW_TRAVEL_ITINERARY_DRAFT,
        /** 確認最近一次旅行行程表草稿。 */
        CONFIRM_TRAVEL_ITINERARY_DRAFT,
        /** 放棄最近一次旅行行程表草稿。 */
        DISCARD_TRAVEL_ITINERARY_DRAFT,
        ASK_LAST_PURCHASE,
        ASK_PRICE_SUMMARY,
        /** 查指定日期範圍的消費明細與可靠總額，可依品項、店家或分類篩選。 */
        ASK_EXPENSE_HISTORY,
        /** 列出最近實際保存的日常繳費紀錄；不包含尚未付款的通知草稿。 */
        ASK_PAYMENT_HISTORY,
        /** 使用者明確宣告兩個標籤的 typed relation；不由模型自行推論。 */
        UPSERT_TAG_RELATION,
        /** 記錄一筆有明確發生時間與標籤的生活事實。 */
        RECORD_TAGGED_LIFE_EVENT,
        /** 沿使用者的 tag graph 唯讀查詢消費與生活事實。 */
        ASK_TAGGED_RECORDS,
        /** 唯讀列出本人保存的原始圖片／文件與 App 授權 content URL。 */
        ASK_STORED_MEDIA,
        /** 唯讀查詢本人由名片保存的外部專業聯絡人。 */
        ASK_CONTACT,
        /** 唯讀查詢本人保存的學校菜單，可依日期範圍、餐別與品項篩選。 */
        ASK_SCHOOL_MEAL,
        /** 保存場館參觀／展出限制；placeName=場館、title=主題，details 只放明確內容。 */
        RECORD_VENUE_VISIT_INFO,
        /** 唯讀查詢本人保存的場館參觀／展出資訊。 */
        ASK_VENUE_VISIT_INFO,
        /** 記錄一次已發生的捐血；startAt=日期，dueAt 只接受使用者明講的下次最早日期。 */
        RECORD_BLOOD_DONATION,
        /** 為最近一筆捐血紀錄補上使用者明確提供的下次最早日期；dueAt 必填。 */
        SET_BLOOD_DONATION_ELIGIBILITY,
        /** 依已保存門檻查某日是否已到日期；startAt 可空，空時查今天。 */
        ASK_BLOOD_DONATION_ELIGIBILITY,
        /** 將最近的商品圖片記為使用用途；必須來自使用者明確補述。 */
        RECORD_PRODUCT_USAGE,
        /** 將最近的商品圖片記為他人推薦，不把圖片本身視為推薦證據。 */
        RECORD_PRODUCT_RECOMMENDATION,
        /** 記錄本人明確回報的商品不適警示，供日後購物提醒；不作醫療診斷。 */
        RECORD_PRODUCT_CAUTION,
        /** 以任意使用者標籤註記最近商品圖片；只有 referenceKind=PURCHASE_REMINDER 才連到購物提醒。 */
        RECORD_PRODUCT_ANNOTATION,
        ASK_FREQUENT_STORE,
        ASK_INVENTORY_EXTREMES,
        CHECK_SHOPPING_INVENTORY,
        LIST_UNPLACED_ITEMS,
        ASK_ITEM_KNOWLEDGE_SUMMARY,
        ASK_SCHEDULE_REMINDER,
        /** 建任務(待辦,無固定時段;可有期限)。 */
        CREATE_TASK,
        /** 建立只有目標日期、沒有固定鐘點的彈性當日任務；startAt 只提供日期。 */
        CREATE_FLEXIBLE_DAY_TASK,
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
        /** 待辦事項轉行程提醒；title 指定既有項目，dueAt 是明確提醒時點。 */
        CONVERT_TASK_TO_SCHEDULE_REMINDER,
        /** 行程提醒轉回無執行日期／時間的待辦事項；title 指定既有項目。 */
        CONVERT_TASK_TO_TODO,
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
        /**
         * 批次刪行程(「把下週行程都刪掉」「刪掉所有行程」):破壞性操作安全閘——
         * 只刪 startAt~endAt 範圍內的非固定行程;固定行程一律保留並提示逐一指名刪。
         * 範圍缺一就留空,由系統回問,不可自行補範圍。
         */
        BULK_CANCEL_SCHEDULES,
        /**
         * 訂餐廳引導(「幫我訂餐廳」「訂位」):系統不能真的訂位,但要走替代路徑——
         * 問齊料理/餐廳、時間、人數與特殊需求後,查營業時間與菜單、給訂位建議。
         * placeName=指定餐廳、title=料理偏好、startAt=用餐時間、
         * options.quantity=人數、options.description=特殊需求;缺的留空由系統回問。
         */
        BOOK_RESTAURANT,
        /** 聽不懂或資訊不足,需要回問。 */
        UNKNOWN
    }
}

package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 意圖 API 整合測試:解析(stub)→ 驗證 → 執行 → 自動綁定/可行性把關的完整鏈。
 */
class IntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService priceRecordService;
    @Autowired
    private com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService taskService;

    private void say(String text, org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var actions = mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "%s"}
                                """.formatted(text)))
                .andExpect(status().isOk());
        for (var matcher : matchers) {
            actions.andExpect(matcher);
        }
    }

    /** 一句話建任務:標題來自 LLM 解析,不是原文照存。 */
    @Test
    void createTaskIntentCreatesTask() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買醬油膏", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));

        say("欸幫我記一下要買醬油膏",
                jsonPath("$.action").value("TASK_CREATED"),
                jsonPath("$.task.title").value("買醬油膏"),
                jsonPath("$.task.status").value("CREATED"));
    }

    /** 一句話建行程:可行 → 直接 CONFIRMED(走真實可行性引擎)。 */
    @Test
    void createScheduleIntentGoesThroughFeasibilityGate() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "剪頭髮", null,
                "2027-06-01T11:00:00+08:00", "2027-06-01T12:00:00+08:00", null, null, null,
                null, null, null, null, null));

        say("下下下週剪頭髮",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"),
                jsonPath("$.schedule.schedule.title").value("剪頭髮"),
                jsonPath("$.schedule.feasible").value(true));
    }

    /** 聽不懂 → 回問,不建任何東西。 */
    @Test
    void unknownIntentAsksForClarification() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null, "請告訴我具體要做什麼",
                null, null, null, null, null));

        say("嗯...那個...",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                // 回覆一律經 IntentReplyFormatter 加上分類 emoji(回問 → ❓)
                jsonPath("$.message").value("❓ 請告訴我具體要做什麼"));
    }

    /** 任務閉環:「買到了」→ 唯一命中的未完成任務被劃掉(CONFIRMED)。 */
    @Test
    void completeTaskIntentConfirmsUniqueMatch() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買閉環測試醬油", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("幫我記買閉環測試醬油");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "閉環測試醬油", null, null, null, null, null, null,
                null, null, null, null, null));
        say("閉環測試醬油買到了",
                jsonPath("$.action").value("TASK_COMPLETED"),
                jsonPath("$.task.title").value("買閉環測試醬油"),
                jsonPath("$.task.status").value("CONFIRMED"));
    }

    /** 多筆符合 → 回問,一件都不動(完成錯任務比多問一句嚴重)。 */
    @Test
    void ambiguousCompleteTaskAsksBack() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買歧義測試鮮奶", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記買歧義測試鮮奶");
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "退歧義測試鮮奶", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記退歧義測試鮮奶");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "歧義測試鮮奶", null, null, null, null, null, null,
                null, null, null, null, null));
        say("歧義測試鮮奶弄好了",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"));
    }

    /** 沒有符合的未完成任務 → 回問,不亂建也不亂劃。 */
    @Test
    void completeTaskWithoutMatchAsksBack() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "根本不存在的事", null, null, null, null, null, null,
                null, null, null, null, null));

        say("根本不存在的事做完了",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"));
    }

    /** 英文任務 + 大小寫不同的關鍵字也要對得上(對話紀錄實際踩過的雷)。 */
    @Test
    void completeTaskKeywordIsCaseInsensitive() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "Buy soy sauce casetest", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("remind me to buy soy sauce casetest");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "buy SOY sauce casetest", null, null, null, null, null, null,
                null, null, null, null, null));
        say("soy sauce casetest買到了",
                jsonPath("$.action").value("TASK_COMPLETED"),
                jsonPath("$.task.status").value("CONFIRMED"));
    }

    /** 同名未結案任務已存在 → 不重複建立,回問(對話紀錄實際踩過的雷)。 */
    @Test
    void duplicateCreateTaskAsksBackInsteadOfCreating() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "重複防呆測試包裹", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記重複防呆測試包裹");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "重複防呆測試包裹", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記重複防呆測試包裹",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不再重複建立")));
    }

    /** 「全部待辦都取消」→ 一次取消全部未結案任務。 */
    @Test
    void cancelAllTasksIntentCancelsEverythingOpen() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "全取消測試甲", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記全取消測試甲");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_ALL_TASKS, null, null, null, null, null, null, null,
                null, null, null, null, null));
        say("全部待辦都取消",
                jsonPath("$.action").value("ALL_TASKS_CANCELED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("全取消測試甲")));
    }

    /** 建地點但 Google 未啟用(測試環境)→ 回覆可行動訊息而非 500/422(webhook 必須 200)。 */
    @Test
    void createPlaceWithoutGoogleRepliesActionableMessage() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_PLACE, null, null, null, null, "建地點測試蝦皮店到店", null, null,
                null, null, null, null, null));
        say("建立地點:建地點測試蝦皮店到店",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("經緯度")));
    }

    /** 任務綁地點閉環:「拿包裹是要到X」→ 綁 geofence;「要去哪拿」→ 回地點。 */
    @Test
    void bindTaskPlaceThenAskWhere() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "拿綁定測試包裹", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("記拿綁定測試包裹");
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "綁定測試蝦皮店到店", "latitude": 24.9670, "longitude": 121.5400}
                                """))
                .andExpect(status().isCreated());

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.BIND_TASK_PLACE, "綁定測試包裹", null, null, null,
                "綁定測試蝦皮店到店", null, null, null, null, null, null, null));
        say("拿綁定測試包裹是要到綁定測試蝦皮店到店",
                jsonPath("$.action").value("TASK_PLACE_BOUND"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已綁定")));

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_TASK_PLACE, "綁定測試包裹", null, null, null, null, null, null,
                null, null, null, null, null));
        say("我要去哪拿綁定測試包裹?",
                jsonPath("$.action").value("TASK_PLACE_INFO"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("綁定測試蝦皮店到店")));
    }

    /** 對系統的抱怨 → FEEDBACK:友善回覆並記錄給開發者。 */
    @Test
    void feedbackIsAcknowledged() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.FEEDBACK, null, null, null, null, null, null, null,
                null, null, null, null, null));
        say("你是不是重複建立任務了",
                jsonPath("$.action").value("FEEDBACK_RECEIVED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("功能改善問題紀錄")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不會建立待辦或行程")));
    }

    /** 取消待辦:「取消買排骨」→ 唯一命中的任務 CANCELED。 */
    @Test
    void cancelTaskIntentCancelsUniqueMatch() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買取消測試排骨", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("幫我記買取消測試排骨");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_TASK, "取消測試排骨", null, null, null, null, null, null,
                null, null, null, null, null));
        say("取消買取消測試排骨",
                jsonPath("$.action").value("TASK_CANCELED"),
                jsonPath("$.task.status").value("CANCELED"));
    }

    /** 改期限:「拿包裹改成11點」→ 期限更新。 */
    @Test
    void rescheduleTaskIntentUpdatesDueDate() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "拿改期測試包裹", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("幫我記拿改期測試包裹");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.RESCHEDULE_TASK, "改期測試包裹", "2027-09-01T11:00:00+08:00",
                null, null, null, null, null, null, null, null, null, null));
        say("拿改期測試包裹改成九月一號11點",
                jsonPath("$.action").value("TASK_RESCHEDULED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("09/01 11:00")),
                jsonPath("$.task.dueAt").value("2027-09-01T03:00:00Z"));
    }

    /** 取消既有行程:唯一命中才取消,不把行程誤當待辦。 */
    @Test
    void cancelScheduleIntentCancelsUniqueMatch() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "取消行程測試午餐", null,
                "2027-09-03T12:00:00+08:00", "2027-09-03T13:00:00+08:00", null, null, null,
                null, null, null, null, null));
        say("幫我排取消行程測試午餐");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_SCHEDULE, "取消行程測試午餐", null,
                null, null, null, null, null, null, null, null, null, null));
        say("取消取消行程測試午餐",
                jsonPath("$.action").value("SCHEDULE_CANCELED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已取消")));
    }

    /** 行程改期未指定結束時間時,保留原本的時長並重新通過可行性關卡。 */
    @Test
    void rescheduleScheduleIntentKeepsOriginalDuration() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "改期行程測試會議", null,
                "2027-09-04T11:00:00+08:00", "2027-09-04T12:30:00+08:00", null, null, null,
                null, null, null, null, null));
        say("幫我排改期行程測試會議");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.RESCHEDULE_SCHEDULE, "改期行程測試會議", null,
                "2027-09-04T14:00:00+08:00", null, null, null, null, null, null, null, null, null));
        say("改期行程測試會議改到下午兩點",
                jsonPath("$.action").value("SCHEDULE_RESCHEDULED"),
                jsonPath("$.schedule.schedule.status").value("CONFIRMED"),
                jsonPath("$.schedule.schedule.startAt").value("2027-09-04T06:00:00Z"),
                jsonPath("$.schedule.schedule.endAt").value("2027-09-04T07:30:00Z"));
    }

    /** 同名行程多筆時先追問日期/時間,不猜測要取消哪一筆。 */
    @Test
    void ambiguousScheduleCancelAsksBack() throws Exception {
        for (String[] times : new String[][]{
                {"歧義行程測試會議上午", "2027-09-05T10:00:00+08:00", "2027-09-05T11:00:00+08:00"},
                {"歧義行程測試會議下午", "2027-09-05T14:00:00+08:00", "2027-09-05T15:00:00+08:00"}}) {
            stub.nextCommand(new IntentCommand(
                    IntentCommand.Type.CREATE_SCHEDULE, times[0], null, times[1], times[2], null, null, null,
                    null, null, null, null, null));
            say("幫我排" + times[0]);
        }

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_SCHEDULE, "歧義行程測試會議", null,
                null, null, null, null, null, null, null, null, null, null));
        say("取消歧義行程測試會議",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2 個行程")));
    }

    /**
     * 使用者實際遇到的一句多操作(intent issue #1):
     * 「取消買A,B也取消,C改到11點」→ 三個操作依序執行,一項都不能漏。
     */
    @Test
    void multiOperationUtteranceExecutesAllCommands() throws Exception {
        for (String title : new String[]{"買批次測試排骨", "買批次測試醬油", "拿批次測試包裹"}) {
            stub.nextCommand(new IntentCommand(
                    IntentCommand.Type.CREATE_TASK, title, null, null, null, null, "NORMAL", null,
                    null, null, null, null, null));
            say("幫我記" + title);
        }

        stub.nextCommands(
                new IntentCommand(IntentCommand.Type.CANCEL_TASK, "批次測試排骨", null,
                        null, null, null, null, null, null, null, null, null, null),
                new IntentCommand(IntentCommand.Type.CANCEL_TASK, "批次測試醬油", null,
                        null, null, null, null, null, null, null, null, null, null),
                new IntentCommand(IntentCommand.Type.RESCHEDULE_TASK, "批次測試包裹", "2027-09-02T11:00:00+08:00",
                        null, null, null, null, null, null, null, null, null, null));
        say("取消買批次測試排骨,批次測試醬油也取消,拿批次測試包裹改成11點",
                jsonPath("$.action").value("BATCH_EXECUTED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("買批次測試排骨」已取消")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("買批次測試醬油」已取消")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("期限改到 09/02 11:00")));
    }

    /** 問地點資訊(intent issue #2):「全聯是指哪一間?」→ 回地址/座標。 */
    @Test
    void askPlaceIntentRepliesPlaceInfo() throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "地點詢問測試全聯", "address": "新北市新店區民權路 42 號",
                                 "latitude": 24.9675, "longitude": 121.5405, "type": "超市"}
                                """))
                .andExpect(status().isCreated());

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PLACE, null, null, null, null, "地點詢問測試全聯", null, null,
                null, null, null, null, null));
        say("地點詢問測試全聯是指哪一間?",
                jsonPath("$.action").value("PLACE_INFO"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("民權路 42 號")));
    }

    /** 問不認識的地點 → 回問,不亂編。 */
    @Test
    void askUnknownPlaceAsksBack() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PLACE, null, null, null, null, "沒建過的神秘地點", null, null,
                null, null, null, null, null));
        say("沒建過的神秘地點是哪裡?",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"));
    }

    /** intent issue #3:沒講「待會」多久 → 回問時窗,同時附預設 3 小時參考。 */
    @Test
    void suggestWithoutWindowAsksAndGivesPreview() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SUGGEST_NEARBY, null, null, null, null, null, null, null,
                null, null, null, null, null));
        say("待會有什麼可以順便做",
                jsonPath("$.action").value("SUGGESTION_MADE"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("你抓多久")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3 小時給你參考")));
    }

    /** 有講時窗(「看2小時」)→ 直接用 2 小時,不回問。 */
    @Test
    void suggestWithExplicitWindowUsesIt() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SUGGEST_NEARBY, null, null, null, null, null, null, null,
                null, null, null, 2, null));
        say("待會兩小時內有什麼可以順便做",
                jsonPath("$.action").value("SUGGESTION_MADE"),
                jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("你抓多久"))));
    }

    /** 固定行程:建立時聽出「每週」→ WEEKLY;查詢細節回「每週固定」。 */
    @Test
    void recurringScheduleCreateAndAskInfo() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "固定意圖測試送課", null,
                "2027-10-06T10:00:00+08:00", "2027-10-06T10:20:00+08:00", null, null, null,
                null, null, null, null, true));
        say("每週三早上十點固定意圖測試送課",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_SCHEDULE_INFO, "固定意圖測試送課", null, null, null, null, null, null,
                null, null, null, null, null));
        say("固定意圖測試送課是固定行程嗎?",
                jsonPath("$.action").value("SCHEDULE_INFO"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("每週固定")));
    }

    /** 既有行程事後設為固定(「送女兒上課是每週固定的」)。 */
    @Test
    void setExistingScheduleRecurring() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "事後固定測試會議", null,
                "2027-10-07T14:00:00+08:00", "2027-10-07T15:00:00+08:00", null, null, null,
                null, null, null, null, null));
        say("下週四下午兩點事後固定測試會議");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SET_SCHEDULE_RECURRING, "事後固定測試會議", null, null, null, null, null, null,
                null, null, null, null, true));
        say("事後固定測試會議是每週固定的",
                jsonPath("$.action").value("SCHEDULE_RECURRENCE_SET"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("每週固定")));
    }

    /** 價格明細查詢(對話紀錄 issue #12):沒有紀錄時引導傳收據。 */
    @Test
    void askPriceHistoryWithoutRecordsGuidesUser() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PRICE_HISTORY, "價格查詢測試奶粉", null, null, null, null, null, null,
                null, null, null, null, null));
        say("列出買價格查詢測試奶粉的明細",
                jsonPath("$.action").value("PRICE_HISTORY"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("沒有")));
    }

    /** 價格明細查詢:有紀錄時列出日期/店家/價格。 */
    @Test
    void askPriceHistoryListsRecords() throws Exception {
        priceRecordService.record("價格明細測試奶粉", "全聯", 899, java.time.LocalDate.parse("2026-07-10"));

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PRICE_HISTORY, "價格明細測試奶粉", null, null, null, null, null, null,
                null, null, null, null, null));
        say("列出買價格明細測試奶粉的明細",
                jsonPath("$.action").value("PRICE_HISTORY"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("899")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("全聯")));
    }

    /** 查待辦:「還有什麼要做」→ 列出未完成任務(含剛建立的)。 */
    @Test
    void listTasksIntentShowsOpenTasks() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買清單查詢測試米", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        say("幫我記買清單查詢測試米");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_TASKS, null, null, null, null, null, null, null,
                null, null, null, null, null));
        say("我還有什麼待辦事項",
                jsonPath("$.action").value("TASKS_LISTED"),
                jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("買清單查詢測試米")));
    }

    /** 查行程:「今天有什麼行程」→ 列出還沒結束的已確認行程。 */
    @Test
    void listSchedulesIntentShowsUpcomingConfirmed() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "清單查詢測試會議", null,
                "2027-08-01T10:00:00+08:00", "2027-08-01T11:00:00+08:00", null, null, null,
                null, null, null, null, null));
        say("八月一號十點清單查詢測試會議");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_SCHEDULES, null, null, null, null, null, null, null,
                null, null, null, null, null));
        say("接下來有什麼行程",
                jsonPath("$.action").value("SCHEDULES_LISTED"),
                jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("清單查詢測試會議")));
    }

    /** 「待會可以順便做」→ 走順路建議(內容依測試資料浮動,只驗路由與動作)。 */
    @Test
    void suggestNearbyIntentReturnsSuggestion() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SUGGEST_NEARBY, null, null, null, null, null, null, null,
                null, null, null, null, null));

        say("待會有什麼可以順便做",
                jsonPath("$.action").value("SUGGESTION_MADE"));
    }

    /** 沒有明確建待辦指示時，LLM 失敗只能告知，不能把普通句子寫進任務。 */
    @Test
    void interpreterFailureDoesNotMutateDataWithoutExplicitCaptureCue() throws Exception {
        // 不呼叫 stub.nextCommand → interpret 會丟例外
        int before = taskService.listTasks().size();

        say("這句話一定要被留下來",
                jsonPath("$.action").value("AI_UNAVAILABLE"),
                jsonPath("$.task").value(org.hamcrest.Matchers.nullValue()),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("我沒有建立任何待辦")));
        org.assertj.core.api.Assertions.assertThat(taskService.listTasks()).hasSize(before);
    }

    /** 明確說「幫我記」時才允許在 LLM 故障期間建立保底待辦。 */
    @Test
    void explicitCaptureCueStillCreatesFallbackTask() throws Exception {
        say("幫我記一下這句話一定要被留下來",
                jsonPath("$.action").value("FALLBACK_TASK_CREATED"),
                jsonPath("$.task.title").value("幫我記一下這句話一定要被留下來"));
    }

    /** LLM 輸出爛時間 → 驗證擋下，但不可再把無效行程誤建成待辦。 */
    @Test
    void invalidCommandTimeDoesNotBecomeTask() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "壞時間行程", null,
                "明天十一點", "後天", null, null, null,
                null, null, null, null, null));

        say("測試爛時間",
                jsonPath("$.action").value("AI_UNAVAILABLE"),
                jsonPath("$.task").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 驗證失敗要保存 AI 欄位，後續問原因時可從對話上下文直接說明。 */
    @Test
    void invalidCommandCanBeExplainedFromConversationContext() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "診斷測試倒垃圾", null,
                null, "2026-07-16T22:15:00+08:00", null, "NORMAL", null,
                null, null, null, null, false));

        say("診斷測試今晚十點倒垃圾",
                jsonPath("$.action").value("AI_UNAVAILABLE"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "建立行程必須提供 startAt")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "type=CREATE_SCHEDULE")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("startAt=(空)")));

        say("為什麼失敗？",
                jsonPath("$.action").value("FAILURE_EXPLAINED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "建立行程必須提供 startAt")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "沒有執行這筆操作")));
    }

    /** 單一明確時點的生活事項被誤判成缺 endAt 行程時，安全降為 timed task。 */
    @Test
    void singlePointChoreWithMissingScheduleEndBecomesTimedTask() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "診斷測試倒垃圾單點", null,
                "2027-07-16T22:00:00+08:00", null, null, "NORMAL", null,
                null, null, null, null, false));

        say("明年今天晚上10點要去倒垃圾",
                jsonPath("$.action").value("TASK_CREATED"),
                jsonPath("$.task.title").value("診斷測試倒垃圾單點"),
                jsonPath("$.task.dueAt").value("2027-07-16T14:00:00Z"));
    }

    /** 使用者實際問句走確定性查詢；即使 stub 沒回覆，也不能建成待辦。 */
    @Test
    void lastExerciseQuestionNeverFallsBackToTask() throws Exception {
        int before = taskService.listTasks().size();

        say("我上次運動是什麼時候",
                jsonPath("$.action").value("RECENT_ACTIVITY_LISTED"),
                jsonPath("$.task").value(org.hamcrest.Matchers.nullValue()));

        org.assertj.core.api.Assertions.assertThat(taskService.listTasks()).hasSize(before);
    }

    /** 指定健身房的歷史查詢不可退化成任何運動；沒有紀錄時要提出安全的放寬選項。 */
    @Test
    void gymHistoryQuestionKeepsBrandScopeAndNeverCreatesTask() throws Exception {
        int before = taskService.listTasks().size();

        say("我之前有去World Gym運動過嗎？",
                jsonPath("$.action").value("RECENT_ACTIVITY_LISTED"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "沒有找到在「World Gym」的運動紀錄")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("其他健身房")),
                jsonPath("$.task").value(org.hamcrest.Matchers.nullValue()));

        org.assertj.core.api.Assertions.assertThat(taskService.listTasks()).hasSize(before);
    }

    /** 歷史活動次數由本機資料統計，沒有紀錄也不能交給故障 fallback 建待辦。 */
    @Test
    void lastMonthExerciseCountNeverFallsBackToTask() throws Exception {
        int before = taskService.listTasks().size();

        say("我上個月運動幾次？",
                jsonPath("$.action").value("ACTIVITY_COUNT_INFO"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("總計｜0 次")),
                jsonPath("$.task").value(org.hamcrest.Matchers.nullValue()));

        org.assertj.core.api.Assertions.assertThat(taskService.listTasks()).hasSize(before);
    }
}

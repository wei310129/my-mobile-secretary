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
                null, null, null));

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
                null, null, null));

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
                null, null, null));

        say("嗯...那個...",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value("請告訴我具體要做什麼"));
    }

    /** 任務閉環:「買到了」→ 唯一命中的未完成任務被劃掉(CONFIRMED)。 */
    @Test
    void completeTaskIntentConfirmsUniqueMatch() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買閉環測試醬油", null, null, null, null, "NORMAL", null,
                null, null, null));
        say("幫我記買閉環測試醬油");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "閉環測試醬油", null, null, null, null, null, null,
                null, null, null));
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
                null, null, null));
        say("記買歧義測試鮮奶");
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "退歧義測試鮮奶", null, null, null, null, "NORMAL", null,
                null, null, null));
        say("記退歧義測試鮮奶");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "歧義測試鮮奶", null, null, null, null, null, null,
                null, null, null));
        say("歧義測試鮮奶弄好了",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"));
    }

    /** 沒有符合的未完成任務 → 回問,不亂建也不亂劃。 */
    @Test
    void completeTaskWithoutMatchAsksBack() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "根本不存在的事", null, null, null, null, null, null,
                null, null, null));

        say("根本不存在的事做完了",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"));
    }

    /** 查待辦:「還有什麼要做」→ 列出未完成任務(含剛建立的)。 */
    @Test
    void listTasksIntentShowsOpenTasks() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "買清單查詢測試米", null, null, null, null, "NORMAL", null,
                null, null, null));
        say("幫我記買清單查詢測試米");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_TASKS, null, null, null, null, null, null, null,
                null, null, null));
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
                null, null, null));
        say("八月一號十點清單查詢測試會議");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_SCHEDULES, null, null, null, null, null, null, null,
                null, null, null));
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
                null, null, null));

        say("待會有什麼可以順便做",
                jsonPath("$.action").value("SUGGESTION_MADE"));
    }

    /** 可靠度鐵律:LLM 失敗(stub 沒塞回覆=模擬炸掉)→ 原文存成任務,不丟資料。 */
    @Test
    void interpreterFailureFallsBackToPlainTask() throws Exception {
        // 不呼叫 stub.nextCommand → interpret 會丟例外

        say("這句話一定要被留下來",
                jsonPath("$.action").value("FALLBACK_TASK_CREATED"),
                jsonPath("$.task.title").value("這句話一定要被留下來"));
    }

    /** LLM 輸出爛時間 → 驗證擋下 → 一樣 fallback,不丟資料。 */
    @Test
    void invalidCommandTimeFallsBackToPlainTask() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "壞時間行程", null,
                "明天十一點", "後天", null, null, null,
                null, null, null));

        say("測試爛時間",
                jsonPath("$.action").value("FALLBACK_TASK_CREATED"),
                jsonPath("$.task.title").value("測試爛時間"));
    }
}

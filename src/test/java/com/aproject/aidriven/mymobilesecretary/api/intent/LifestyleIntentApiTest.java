package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class LifestyleIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Test
    void recurringCategorizedTaskIsPersisted() throws Exception {
        stub.nextCommand(command(IntentCommand.Type.CREATE_TASK, "每月生活費測試",
                "2030-08-05T09:00:00+08:00", null, null, null,
                options(null, null, null, null, "MONTHLY", "FINANCE", null, null, null, null)));

        say("每月五號提醒我處理生活費",
                jsonPath("$.action").value("TASK_CREATED"),
                jsonPath("$.task.category").value("FINANCE"),
                jsonPath("$.task.recurrence").value("MONTHLY"));
    }

    @Test
    void shoppingListDeduplicatesAndTracksInventory() throws Exception {
        String eggs = "生活測試雞蛋";
        String milk = "生活測試牛奶";
        stub.nextCommand(command(IntentCommand.Type.ADD_SHOPPING_ITEMS, null,
                null, null, null, null,
                options(null, null, null, null, null, null, List.of(eggs, milk), null, null, null)));
        say("購物清單加雞蛋跟牛奶", jsonPath("$.action").value("SHOPPING_ITEMS_ADDED"));

        stub.nextCommand(command(IntentCommand.Type.ADD_SHOPPING_ITEMS, null,
                null, null, null, null,
                options(null, null, null, null, null, null, List.of(milk), null, null, null)));
        say("牛奶也加一下", jsonPath("$.action").value("SHOPPING_ITEMS_ADDED"));

        stub.nextCommand(command(IntentCommand.Type.SET_INVENTORY, null,
                null, null, null, null,
                options(null, null, null, null, null, null, List.of(milk), 2, null, null)));
        say("家裡還有兩瓶牛奶", jsonPath("$.action").value("INVENTORY_UPDATED"),
                jsonPath("$.message").value(containsString("2")));

        stub.nextCommand(command(IntentCommand.Type.LIST_SHOPPING_ITEMS, null,
                null, null, null, null, IntentOptions.empty()));
        // 回報家中還有庫存(>0)的品項就不算「缺」,會自動移出購物清單
        say("我還缺什麼", jsonPath("$.action").value("SHOPPING_LISTED"),
                jsonPath("$.message").value(containsString(eggs)),
                jsonPath("$.message").value(not(containsString(milk))));

        stub.nextCommand(command(IntentCommand.Type.MARK_SHOPPING_PURCHASED, null,
                null, null, null, null, itemOptions(List.of(eggs))));
        say("雞蛋買到了", jsonPath("$.action").value("SHOPPING_ITEMS_PURCHASED"),
                jsonPath("$.message").value(containsString(eggs)));
    }

    @Test
    void scheduleReminderAndFreeSlotPlanningWork() throws Exception {
        stub.nextCommand(command(IntentCommand.Type.CREATE_SCHEDULE, "生活測試會議",
                null, "2030-08-10T15:00:00+08:00", "2030-08-10T16:00:00+08:00",
                null, IntentOptions.empty()));
        say("排一個測試會議", jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(command(IntentCommand.Type.ADD_SCHEDULE_REMINDER, "生活測試會議",
                null, null, null, null,
                options(null, null, null, 15, null, null, null, null, null, null)));
        say("會議前十五分鐘提醒", jsonPath("$.action").value("SCHEDULE_REMINDER_CREATED"),
                jsonPath("$.message").value(containsString("15")));

        stub.nextCommand(command(IntentCommand.Type.SUGGEST_FREE_SLOT, null,
                null, "2030-08-10T09:00:00+08:00", "2030-08-10T18:00:00+08:00", null,
                options(null, null, 60, null, null, null, null, null, "AFTERNOON", null)));
        say("找一個一小時空檔", jsonPath("$.action").value("FREE_SLOTS_SUGGESTED"));

        stub.nextCommand(command(IntentCommand.Type.RESIZE_SCHEDULE, "生活測試會議",
                null, null, null, null, resizeOptions(null, 30)));
        say("會議延長半小時", jsonPath("$.action").value("SCHEDULE_RESIZED"));
    }

    @Test
    void recurringScheduleCutoffFlowsFromIntentToConfirmation() throws Exception {
        IntentOptions options = new IntentOptions(
                null, null, null, null, null, null, "WEEKLY", null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "2030-09-30");
        stub.nextCommand(command(IntentCommand.Type.CREATE_SCHEDULE, "每週英文課",
                null, "2030-08-10T09:00:00+08:00", "2030-08-10T10:00:00+08:00",
                null, options));

        say("每週六上午九點上英文課到九月底",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"),
                jsonPath("$.schedule.schedule.recurrence").value("WEEKLY"),
                jsonPath("$.schedule.schedule.recurrenceUntil").value("2030-09-30"),
                jsonPath("$.message").value(containsString("2030/09/30")));
    }

    @Test
    void recurringScheduleCanSkipOnceWithoutLeavingItsReminder() throws Exception {
        IntentOptions recurring = new IntentOptions(
                null, null, null, null, null, null, "WEEKLY", null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "2031-12-31");
        stub.nextCommand(command(IntentCommand.Type.CREATE_SCHEDULE, "生活測試英文課停一次",
                null, "2031-10-04T09:00:00+08:00", "2031-10-04T10:00:00+08:00",
                null, recurring));
        say("每週六上午九點上英文課",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(command(IntentCommand.Type.ADD_SCHEDULE_REMINDER, "生活測試英文課停一次",
                null, null, null, null,
                options(null, null, null, 15, null, null, null, null, null, null)));
        say("英文課前十五分鐘提醒",
                jsonPath("$.action").value("SCHEDULE_REMINDER_CREATED"));

        stub.nextCommand(command(IntentCommand.Type.SKIP_RECURRING_OCCURRENCE,
                "生活測試英文課停一次", null, null, null, null, scheduleReferenceOptions()));
        say("這週六英文課先不上,下週照常",
                jsonPath("$.action").value("SCHEDULE_RECURRENCE_SKIPPED"),
                jsonPath("$.message").value(containsString("下一次")),
                jsonPath("$.message").value(containsString("已取消這一場的 1 個相關提醒")));
    }

    @Test
    void recurringScheduleCanRescheduleOnlyThisOccurrence() throws Exception {
        IntentOptions recurring = new IntentOptions(
                null, null, null, null, null, null, "WEEKLY", null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "2032-12-31");
        stub.nextCommand(command(IntentCommand.Type.CREATE_SCHEDULE, "生活測試英文課只改本次",
                null, "2032-10-02T09:00:00+08:00", "2032-10-02T10:00:00+08:00",
                null, recurring));
        say("每週六上午九點上英文課",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(command(IntentCommand.Type.RESCHEDULE_SCHEDULE,
                "生活測試英文課只改本次", null, "2032-10-02T11:00:00+08:00", null,
                null, recurrenceScopeOptions("THIS_OCCURRENCE")));
        say("這週英文課改十一點,下週照舊",
                jsonPath("$.action").value("SCHEDULE_RESCHEDULED"),
                jsonPath("$.schedule.schedule.recurrence").value("NONE"),
                jsonPath("$.message").value(containsString("只把")),
                jsonPath("$.message").value(containsString("10/09 09:00")));
    }

    @Test
    void recurringScheduleRescheduleAsksWhenScopeIsMissing() throws Exception {
        IntentOptions recurring = new IntentOptions(
                null, null, null, null, null, null, "WEEKLY", null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "2033-12-31");
        stub.nextCommand(command(IntentCommand.Type.CREATE_SCHEDULE, "生活測試固定改期要確認",
                null, "2033-10-01T09:00:00+08:00", "2033-10-01T10:00:00+08:00",
                null, recurring));
        say("每週六上午九點固定會議",
                jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(command(IntentCommand.Type.RESCHEDULE_SCHEDULE,
                "生活測試固定改期要確認", null, "2033-10-01T11:00:00+08:00", null,
                null, IntentOptions.empty()));
        say("固定會議改十一點",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(containsString("只改本次")),
                jsonPath("$.message").value(containsString("之後每一次")));
    }

    @Test
    void recurringTaskCanBePausedResumedAndUpdated() throws Exception {
        String title = "生活測試週報";
        // 「每週」沒講週幾必須回問,不可自行定時點(使用者 2026-07-16 裁決),且不得建立任務
        stub.nextCommand(command(IntentCommand.Type.CREATE_TASK, title,
                "2030-08-09T09:00:00+08:00", null, null, null,
                options(null, null, null, null, "WEEKLY", "WORK", null, null, null, null)));
        say("每週提醒我交週報", jsonPath("$.action").value("CLARIFICATION_NEEDED"));

        stub.nextCommand(command(IntentCommand.Type.CREATE_TASK, title,
                "2030-08-09T09:00:00+08:00", null, null, null,
                options(null, null, null, null, "WEEKLY", "WORK", null, null, null, null)));
        say("每週五提醒我交週報", jsonPath("$.action").value("TASK_CREATED"));

        stub.nextCommand(command(IntentCommand.Type.PAUSE_RECURRING_TASK, title,
                null, null, null, null, IntentOptions.empty()));
        say("週報提醒先暫停", jsonPath("$.action").value("RECURRENCE_PAUSED"),
                jsonPath("$.task.recurrencePaused").value(true));

        stub.nextCommand(command(IntentCommand.Type.RESUME_RECURRING_TASK, title,
                null, null, null, null, IntentOptions.empty()));
        say("恢復週報提醒", jsonPath("$.action").value("RECURRENCE_RESUMED"),
                jsonPath("$.task.recurrencePaused").value(false));

        stub.nextCommand(new IntentCommand(IntentCommand.Type.UPDATE_TASK, title,
                null, null, null, null, "HIGH", null, null, null,
                null, null, null, updateOptions("生活測試重要週報", "記得附數據", "WORK")));
        say("週報改名並設為緊急", jsonPath("$.action").value("TASK_UPDATED"),
                jsonPath("$.task.title").value("生活測試重要週報"),
                jsonPath("$.task.priority").value("HIGH"));
    }

    @Test
    void weatherConditionPlanningPreferenceAndSocialReplyDoNotBecomeFallbackTasks() throws Exception {
        stub.nextCommand(command(IntentCommand.Type.CREATE_WEATHER_REMINDER, "生活測試帶傘",
                "2030-08-12T08:00:00+08:00", null, null, null,
                options(null, null, null, null, null, "PERSONAL", null, null, null, "RAIN")));
        say("如果下雨提醒我帶傘", jsonPath("$.action").value("WEATHER_REMINDER_CREATED"));

        stub.nextCommand(command(IntentCommand.Type.SET_PLANNING_BUFFER, null,
                null, null, null, null,
                options(null, null, 45, null, null, null, null, null, null, null, 20)));
        say("幫我留吃飯跟交通時間", jsonPath("$.action").value("PLANNING_PREFERENCE_SET"),
                jsonPath("$.message").value(containsString("20")),
                jsonPath("$.message").value(containsString("45")));

        stub.nextCommand(command(IntentCommand.Type.SOCIAL, null,
                null, null, null, null, IntentOptions.empty()));
        say("謝謝今天先這樣", jsonPath("$.action").value("SOCIAL_REPLIED"));
    }

    private void say(String text, org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var result = mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"%s\"}".formatted(text)))
                .andExpect(status().isOk());
        for (var matcher : matchers) {
            result.andExpect(matcher);
        }
    }

    private static IntentCommand command(IntentCommand.Type type, String title, String dueAt,
                                         String startAt, String endAt, String placeName,
                                         IntentOptions options) {
        return new IntentCommand(type, title, dueAt, startAt, endAt, placeName,
                "NORMAL", null, null, null, null, null, null, options);
    }

    private static IntentOptions options(String filter, Integer ordinal, Integer duration,
                                         Integer lead, String recurrence, String category,
                                         List<String> items, Integer quantity, String timeOfDay,
                                         String condition) {
        return options(filter, ordinal, duration, lead, recurrence, category, items, quantity,
                timeOfDay, condition, null);
    }

    private static IntentOptions options(String filter, Integer ordinal, Integer duration,
                                         Integer lead, String recurrence, String category,
                                         List<String> items, Integer quantity, String timeOfDay,
                                         String condition, Integer buffer) {
        return new IntentOptions(filter, ordinal, duration, lead, null, null, recurrence,
                category, items, quantity, null, null, timeOfDay, null, null, condition,
                null, buffer, null, null, null, null);
    }

    private static IntentOptions itemOptions(List<String> items) {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                items, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static IntentOptions resizeOptions(Integer duration, Integer shift) {
        return new IntentOptions(null, null, duration, null, null, null, null, null,
                null, null, null, null, null, null, shift, null, null, null,
                null, null, null, null);
    }

    private static IntentOptions updateOptions(String newTitle, String description, String category) {
        return new IntentOptions(null, null, null, null, null, null, null, category,
                null, null, null, null, null, null, null, null, null, null,
                null, null, newTitle, description);
    }

    private static IntentOptions scheduleReferenceOptions() {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                null, null, null, "SCHEDULE", null, null, null, null, null, null,
                null, null, null, null);
    }

    private static IntentOptions recurrenceScopeOptions(String scope) {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, scope);
    }
}

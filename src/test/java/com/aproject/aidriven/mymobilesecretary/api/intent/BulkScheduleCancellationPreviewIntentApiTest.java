package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Scenario #161: private bulk cancellation is previewed and confirmed in two turns. */
class BulkScheduleCancellationPreviewIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void onlyPreviewedPersonalOneTimeScheduleIsCanceledAfterExplicitConfirmation()
            throws Exception {
        ScheduleItem personal = schedule("私人聚餐", "2026-07-21T18:00:00+08:00",
                ScheduleItem.Category.PERSONAL, ScheduleItem.Recurrence.NONE);
        ScheduleItem work = schedule("公司評審", "2026-07-22T10:00:00+08:00",
                ScheduleItem.Category.WORK, ScheduleItem.Recurrence.NONE);
        ScheduleItem family = schedule("小孩回診", "2026-07-23T15:00:00+08:00",
                ScheduleItem.Category.FAMILY, ScheduleItem.Recurrence.NONE);
        ScheduleItem unknown = schedule("歷史未分類", "2026-07-24T14:00:00+08:00",
                ScheduleItem.Category.UNKNOWN, ScheduleItem.Recurrence.NONE);
        ScheduleItem recurring = schedule("每週私人運動", "2026-07-20T19:00:00+08:00",
                ScheduleItem.Category.PERSONAL, ScheduleItem.Recurrence.WEEKLY);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.BULK_CANCEL_SCHEDULES, null, null,
                "2026-07-20T00:00:00+08:00", "2026-07-27T00:00:00+08:00",
                null, null, null, null, null, null, null, null,
                IntentOptions.empty().withFilter("PREVIEW_PRIVATE_ONLY")));

        say("把下週所有不是固定的私人行程刪掉，公司跟小孩的都留著，先列清單給我確認後才能刪")
                .andExpect(jsonPath("$.action").value("SCHEDULE_CANCELLATION_PREVIEWED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("私人聚餐")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("公司評審")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("小孩回診")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("歷史未分類")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("每週私人運動")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("目前尚未刪除")));

        assertStatus(personal, ScheduleStatus.CONFIRMED);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ACCEPT_CONTEXT, null, null, null, null,
                null, null, null, null, null, null, null, null));
        say("確認刪除剛才清單")
                .andExpect(jsonPath("$.action").value("SCHEDULES_BULK_CANCELED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("私人聚餐")));

        assertStatus(personal, ScheduleStatus.CANCELED);
        assertStatus(work, ScheduleStatus.CONFIRMED);
        assertStatus(family, ScheduleStatus.CONFIRMED);
        assertStatus(unknown, ScheduleStatus.CONFIRMED);
        assertStatus(recurring, ScheduleStatus.CONFIRMED);
    }

    private ScheduleItem schedule(
            String title, String startAt,
            ScheduleItem.Category category, ScheduleItem.Recurrence recurrence) {
        Instant start = Instant.parse(startAt);
        return scheduleService.createSchedule(
                title, start, start.plusSeconds(3600), null,
                recurrence, null, category).item();
    }

    private void assertStatus(ScheduleItem item, ScheduleStatus expected) {
        assertThat(scheduleService.getSchedule(item.getId()).getStatus()).isEqualTo(expected);
    }

    private org.springframework.test.web.servlet.ResultActions say(String text) throws Exception {
        return mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"%s"}
                                """.formatted(text)))
                .andExpect(status().isOk());
    }
}

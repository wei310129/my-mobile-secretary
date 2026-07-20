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

/** Scenario #162: person, topic, exclusion and ordering narrow cancellation to one schedule. */
class ScheduleCancellationDisambiguationIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void cancelsOnlyTheContractMeetingAndKeepsProductMeeting() throws Exception {
        ScheduleItem product = schedule("跟王經理產品會議", "2099-07-18T13:00:00+08:00");
        ScheduleItem anotherManager = schedule("跟李經理談合約會議", "2099-07-18T14:00:00+08:00");
        ScheduleItem contract = schedule("跟王經理談合約會議", "2099-07-18T15:00:00+08:00");
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_SCHEDULE, "會議", null,
                "2099-07-18T12:00:00+08:00", "2099-07-18T18:00:00+08:00",
                null, null, null, null, null, null, null, null,
                cancellationOptions("王經理", "EXCLUDE:產品會", 2)));

        say("今天下午那個跟王經理的會議取消，不是產品會，是後面那場談合約的，產品會照常")
                .andExpect(jsonPath("$.action").value("SCHEDULE_CANCELED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("談合約")));

        assertStatus(contract, ScheduleStatus.CANCELED);
        assertStatus(product, ScheduleStatus.CONFIRMED);
        assertStatus(anotherManager, ScheduleStatus.CONFIRMED);
    }

    @Test
    void nonUniqueContractMeetingsAskForMoreDetailWithoutCanceling() throws Exception {
        ScheduleItem first = schedule("跟王經理談合約會議 A", "2099-07-18T14:00:00+08:00");
        ScheduleItem second = schedule("跟王經理談合約會議 B", "2099-07-18T15:00:00+08:00");
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CANCEL_SCHEDULE, "談合約會議", null,
                "2099-07-18T12:00:00+08:00", "2099-07-18T18:00:00+08:00",
                null, null, null, null, null, null, null, null,
                cancellationOptions("王經理", null, null)));

        say("今天下午跟王經理的談合約會議取消")
                .andExpect(jsonPath("$.action").value("CLARIFICATION_NEEDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("有 2 個行程都符合")));

        assertStatus(first, ScheduleStatus.CONFIRMED);
        assertStatus(second, ScheduleStatus.CONFIRMED);
    }

    private ScheduleItem schedule(String title, String startAt) {
        Instant start = Instant.parse(startAt);
        return scheduleService.createSchedule(
                title, start, start.plusSeconds(3600), null, false).item();
    }

    private void assertStatus(ScheduleItem item, ScheduleStatus expected) {
        assertThat(scheduleService.getSchedule(item.getId()).getStatus()).isEqualTo(expected);
    }

    private static IntentOptions cancellationOptions(
            String referenceTitle, String filter, Integer ordinal) {
        return new IntentOptions(filter, ordinal, null, null, null, null, null, null,
                null, null, referenceTitle, null, null, null, null, null, null, null,
                null, null, null, null);
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

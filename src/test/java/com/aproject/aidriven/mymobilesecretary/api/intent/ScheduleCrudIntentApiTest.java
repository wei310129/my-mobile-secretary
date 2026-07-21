package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class ScheduleCrudIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private ScheduleService schedules;

    @Test
    void updateScheduleRenamesTheExistingRecord() throws Exception {
        ScheduleItem source = schedule("看牙醫", "2099-08-01T02:00:00Z");
        stub.nextCommand(command(IntentCommand.Type.UPDATE_SCHEDULE, "看牙醫", null,
                IntentOptions.empty().withNewTitle("定期洗牙")));

        say("把看牙醫改名成定期洗牙");

        assertThat(schedules.getSchedule(source.getId()).getTitle()).isEqualTo("定期洗牙");
    }

    @Test
    void copyScheduleReusesDurationWithoutChangingTheSource() throws Exception {
        ScheduleItem source = schedule("看牙醫", "2099-08-01T02:00:00Z");
        stub.nextCommand(command(IntentCommand.Type.COPY_SCHEDULE, null,
                "2099-09-15T15:00:00+08:00",
                IntentOptions.empty().withReferenceTitle("看牙醫")));

        say("照上次看牙醫的資料建立9月15日下午三點的新行程");

        var matches = schedules.findReschedulableSchedulesMatching("看牙醫");
        assertThat(matches).hasSize(2);
        assertThat(matches).anyMatch(item -> item.getId().equals(source.getId())
                && item.getStartAt().equals(Instant.parse("2099-08-01T02:00:00Z")));
        assertThat(matches).anyMatch(item -> item.getStartAt()
                .equals(Instant.parse("2099-09-15T07:00:00Z"))
                && java.time.Duration.between(item.getStartAt(), item.getEndAt()).toHours() == 1);
    }

    @Test
    void mergeKeepsTheNamedPrimaryAndTerminatesTheDuplicate() throws Exception {
        ScheduleItem kept = schedule("送女兒到夏恩英語上課", "2099-08-01T02:00:00Z");
        ScheduleItem duplicate = schedule("女兒上夏恩英語", "2099-08-01T02:00:00Z");
        stub.nextCommand(command(IntentCommand.Type.MERGE_SCHEDULES, kept.getTitle(), null,
                IntentOptions.empty().withReferenceTitle(duplicate.getTitle())));

        say("把女兒上夏恩英語合併到送女兒到夏恩英語上課，保留後者");

        assertThat(schedules.getSchedule(kept.getId()).getStatus()).isEqualTo(ScheduleStatus.CONFIRMED);
        assertThat(schedules.getSchedule(duplicate.getId()).getStatus()).isEqualTo(ScheduleStatus.REJECTED);
    }

    private ScheduleItem schedule(String title, String startAt) {
        Instant start = Instant.parse(startAt);
        return schedules.createSchedule(title, start, start.plusSeconds(3600), null, false).item();
    }

    private static IntentCommand command(
            IntentCommand.Type type, String title, String startAt, IntentOptions options) {
        return new IntentCommand(type, title, null, startAt, null, null, null, null,
                null, null, null, null, null, options);
    }

    private void say(String text) throws Exception {
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"%s"}
                                """.formatted(text)))
                .andExpect(status().isOk());
    }
}

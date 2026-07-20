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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Scenario #160: inspect an expanded hypothetical window without persisting it. */
class HypotheticalFeasibilityIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void preparationMeetingAndTravelAreCheckedSeparatelyWithoutCreatingMeeting() throws Exception {
        confirmed("送小孩", "2026-07-19T08:30:00+08:00", "2026-07-19T08:50:00+08:00");
        confirmed("每日站會", "2026-07-19T09:15:00+08:00", "2026-07-19T09:30:00+08:00");
        confirmed("接家人", "2026-07-19T10:20:00+08:00", "2026-07-19T10:50:00+08:00");
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CHECK_FEASIBILITY, "假設客戶會議", null,
                "2026-07-19T09:00:00+08:00", "2026-07-19T10:00:00+08:00",
                null, null, null, null, null, null, null, null,
                IntentOptions.empty().withHypotheticalBuffers(20, 40)));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"明天早上九點開會一小時，先不要建，幫我看前面準備二十分鐘加後面交通四十分鐘會不會撞到"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CONNECTION_CHECKED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只做檢查，未建立行程")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("08:40–10:40")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("準備段與「送小孩」")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("主行程與「每日站會」")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("後續交通段與「接家人」")));

        assertThat(scheduleService.findReschedulableSchedulesMatching("假設客戶會議"))
                .isEmpty();
    }

    private void confirmed(String title, String startAt, String endAt) {
        var decision = scheduleService.createSchedule(
                title, Instant.parse(startAt), Instant.parse(endAt), null);
        assertThat(decision.item().getStatus().name()).isEqualTo("CONFIRMED");
    }
}

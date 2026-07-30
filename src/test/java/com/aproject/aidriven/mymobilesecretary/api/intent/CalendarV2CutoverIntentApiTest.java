package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarV2CutoverIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createAndListUseOnlyCalendarV2WithoutLegacyDualWrite() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "W11 切換驗證會議",
                null,
                "2026-08-03T09:00:00+08:00",
                "2026-08-03T10:00:00+08:00",
                null, null, null, null, null, null, null, null));

                mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"8月3日上午九點建立 W11 切換驗證會議\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        assertThat(count("calendar_plan", "W11 切換驗證會議")).isEqualTo(1);
        assertThat(count("schedule_item", "W11 切換驗證會議")).isZero();

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
                null,
                null,
                "2026-08-03T00:00:00+08:00",
                null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"8月3日有什麼行程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULES_LISTED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("W11 切換驗證會議")));

        assertThat(count("calendar_plan", "W11 切換驗證會議")).isEqualTo(1);
        assertThat(count("schedule_item", "W11 切換驗證會議")).isZero();
    }

    private long count(String table, String title) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE title = ?",
                Long.class,
                title);
    }
}

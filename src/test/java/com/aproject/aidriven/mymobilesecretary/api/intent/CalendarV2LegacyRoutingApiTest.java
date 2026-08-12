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

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=false")
class CalendarV2LegacyRoutingApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void disabledCutoverPreservesLegacyCreateWithoutCalendarV2DualWrite() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "W11 關閉旗標會議",
                null,
                "2099-08-04T09:00:00+08:00",
                "2099-08-04T10:00:00+08:00",
                null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"2099年8月4日上午九點建立 W11 關閉旗標會議\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        assertThat(count("schedule_item", "W11 關閉旗標會議")).isEqualTo(1);
        assertThat(count("calendar_plan", "W11 關閉旗標會議")).isZero();
    }

    @Test
    void disabledCutoverCannotTurnStartOnlyEvidenceIntoGuessedLegacyInterval()
            throws Exception {
        String title = "W11 舊版單點阻擋";
        String source = "2099年8月5日上午十一點進行 W11 舊版單點阻擋";
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                "2099-08-05T11:00:00+08:00",
                "2099-08-05T12:00:00+08:00",
                null, null, null, null, null, null, null, false, null, source));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(java.util.Map.of("text", source))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CLARIFICATION_NEEDED"));

        assertThat(count("schedule_item", title)).isZero();
        assertThat(count("calendar_plan", title)).isZero();
    }

    private long count(String table, String title) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE title = ?",
                Long.class,
                title);
    }
}

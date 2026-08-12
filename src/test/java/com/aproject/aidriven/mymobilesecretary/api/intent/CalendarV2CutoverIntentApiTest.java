package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
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

    @Autowired
    private Clock clock;

    @Test
    void createAndListUseOnlyCalendarV2WithoutLegacyDualWrite() throws Exception {
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        ZonedDateTime start = LocalDate.now(clock.withZone(taipei)).plusDays(1)
                .atTime(9, 0).atZone(taipei);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "W11 切換驗證會議",
                null,
                start.toOffsetDateTime().toString(),
                start.plusHours(1).toOffsetDateTime().toString(),
                null, null, null, null, null, null, null, null));

                mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"明天上午九點建立 W11 切換驗證會議\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        assertThat(count("calendar_plan", "W11 切換驗證會議")).isEqualTo(1);
        assertThat(count("schedule_item", "W11 切換驗證會議")).isZero();

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
                null,
                null,
                start.toLocalDate().atStartOfDay(taipei).toOffsetDateTime().toString(),
                null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"明天有什麼行程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULES_LISTED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("W11 切換驗證會議")));

        assertThat(count("calendar_plan", "W11 切換驗證會議")).isEqualTo(1);
        assertThat(count("schedule_item", "W11 切換驗證會議")).isZero();
    }

    @Test
    void typedScheduleLookupWinsOverImplicitTaggedLifeRecordShortcut() throws Exception {
        String title = "Port Checkin";
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        ZonedDateTime start = LocalDate.now(clock.withZone(taipei)).plusDays(2)
                .atTime(8, 0).atZone(taipei);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, title, null,
                start.toOffsetDateTime().toString(), start.plusHours(1).toOffsetDateTime().toString(),
                null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("create Port Checkin two days from now at 8 AM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_SCHEDULE_INFO, title,
                null, null, null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("\u67e5\u4e00\u4e0bPort Checkin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_INFO"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(title)));

        assertThat(count("calendar_plan", title)).isEqualTo(1);
        assertThat(count("schedule_item", title)).isZero();

        stub.clear();
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("\u67e5\u4e00\u4e0bPort Checkin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TAGGED_RECORDS_INFO"));
    }

    @Test
    void startOnlyEvidencePersistsTimedPointInsteadOfModelInventedDuration() throws Exception {
        String title = "W11 單一時點驗證";
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        ZonedDateTime start = LocalDate.now(clock.withZone(taipei)).plusDays(3)
                .atTime(11, 0).atZone(taipei);
        String source = "%d月%d日上午十一點進行 W11 單一時點驗證"
                .formatted(start.getMonthValue(), start.getDayOfMonth());
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                start.toOffsetDateTime().toString(),
                start.plusHours(1).toOffsetDateTime().toString(),
                null, null, null, null, null, null, null, false, null, source));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(source)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("12:00"))));

        assertThat(jdbc.queryForObject(
                "SELECT placement_kind FROM calendar_plan WHERE title = ?",
                String.class,
                title)).isEqualTo("TIMED_POINT");
        assertThat(count("schedule_item", title)).isZero();
    }

    @Test
    void recurringCreatePersistsOneCalendarSeriesWithoutLegacyDualWrite() throws Exception {
        String title = "W11 每週課程";
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        ZonedDateTime start = LocalDate.now(clock.withZone(taipei))
                .with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                .atTime(14, 0).atZone(taipei);
        String source = "%d月%d日每週六下午兩點到四點上 W11 陶藝課"
                .formatted(start.getMonthValue(), start.getDayOfMonth());
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                start.toOffsetDateTime().toString(),
                start.plusHours(2).toOffsetDateTime().toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                source));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(source)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SCHEDULE_CONFIRMED"));

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_recurrence_series series
                        JOIN calendar_plan plan ON plan.id = series.plan_id
                        WHERE plan.title = ?
                        """,
                        Long.class,
                        title))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_recurrence_rule_revision revision
                        JOIN calendar_recurrence_series series
                          ON series.id = revision.series_id
                        JOIN calendar_plan plan ON plan.id = series.plan_id
                        WHERE plan.title = ? AND revision.frequency = 'WEEKLY'
                        """,
                        Long.class,
                        title))
                .isEqualTo(1L);
        assertThat(count("schedule_item", title)).isZero();
    }

    private static String payload(String text) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.Map.of("text", text));
    }

    private long count(String table, String title) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE title = ?",
                Long.class,
                title);
    }
}

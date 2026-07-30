package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarIcsWriterTest {

    @Test
    void compactSnapshotIsDeterministicEscapedAndUsesCrLf() {
        UUID planId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var event = new CalendarIcsEvent(
                planId,
                "週六,行程\n集合",
                "節點一;節點二",
                Instant.parse("2026-08-01T01:00:00Z"),
                Instant.parse("2026-08-01T03:00:00Z"),
                ZoneId.of("Asia/Taipei"));

        byte[] first = CalendarIcsWriter.write(List.of(event));
        byte[] replay = CalendarIcsWriter.write(List.of(event));
        String text = new String(first, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(first).isEqualTo(replay);
        assertThat(text)
                .startsWith("BEGIN:VCALENDAR\r\n")
                .contains("UID:calendar-plan-" + planId + "@my-mobile-secretary\r\n")
                .contains("SUMMARY:週六\\,行程\\n集合\r\n")
                .contains("DESCRIPTION:節點一\\;節點二\r\n")
                .contains("DTSTART;TZID=Asia/Taipei:20260801T090000\r\n")
                .contains("DTEND;TZID=Asia/Taipei:20260801T110000\r\n")
                .endsWith("END:VCALENDAR\r\n");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
        assertThat(text)
                .doesNotContain("roster")
                .doesNotContain("route")
                .doesNotContain("ATTENDEE");
    }

    @Test
    void allDayActivityUsesExclusiveDateBoundary() {
        UUID activityId =
                UUID.fromString("20000000-0000-0000-0000-000000000002");
        String text = new String(
                CalendarIcsWriter.write(List.of(CalendarIcsEvent.allDay(
                        activityId,
                        "activity",
                        "全天活動",
                        "",
                        LocalDate.of(2026, 8, 2),
                        LocalDate.of(2026, 8, 4)))),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text)
                .contains("UID:calendar-activity-" + activityId)
                .contains("DTSTART;VALUE=DATE:20260802\r\n")
                .contains("DTEND;VALUE=DATE:20260804\r\n");
    }

    @Test
    void actorOwnedOffsetReminderMapsToDisplayAlarm() {
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        var event = new CalendarIcsEvent(
                planId,
                "有提醒的行程",
                "",
                Instant.parse("2026-08-01T01:00:00Z"),
                Instant.parse("2026-08-01T02:00:00Z"),
                ZoneId.of("Asia/Taipei"));

        String text = new String(
                CalendarIcsWriter.write(
                        List.of(event),
                        Map.of(planId, List.of(Duration.ofMinutes(30)))),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text)
                .contains("BEGIN:VALARM\r\n")
                .contains("TRIGGER:-PT30M\r\n")
                .contains("ACTION:DISPLAY\r\n")
                .contains("END:VALARM\r\n");
    }
}

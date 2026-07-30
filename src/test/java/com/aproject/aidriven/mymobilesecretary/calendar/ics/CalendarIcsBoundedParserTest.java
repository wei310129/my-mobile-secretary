package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CalendarIcsBoundedParserTest {

    @Test
    void parsesTimedEventAndRelativeAlarmWithoutExecutingInertReferences() {
        CalendarIcsImportDocument document = parse("""
                BEGIN:VCALENDAR
                VERSION:2.0
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:event-1@example.test
                SEQUENCE:4
                DTSTART;TZID=Asia/Taipei:20260731T090000
                DTEND;TZID=Asia/Taipei:20260731T100000
                SUMMARY:Team\\, sync
                DESCRIPTION:First line\\nSecond line
                LOCATION:Room\\; 2
                URL:https://example.test/never-fetch
                ATTACH:https://example.test/never-fetch-either
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """);

        assertThat(document.method()).isEqualTo("PUBLISH");
        assertThat(document.candidates()).hasSize(1);
        CalendarIcsImportCandidate candidate = document.candidates().getFirst();
        assertThat(candidate.uid()).isEqualTo("event-1@example.test");
        assertThat(candidate.sequence()).isEqualTo(4);
        assertThat(candidate.title()).isEqualTo("Team, sync");
        assertThat(candidate.description()).isEqualTo("First line\nSecond line");
        assertThat(candidate.location()).isEqualTo("Room; 2");
        assertThat(candidate.startsAt()).isEqualTo(Instant.parse("2026-07-31T01:00:00Z"));
        assertThat(candidate.endsAt()).isEqualTo(Instant.parse("2026-07-31T02:00:00Z"));
        assertThat(candidate.zoneId()).isEqualTo(ZoneId.of("Asia/Taipei"));
        assertThat(candidate.reminderSuggestions()).containsExactly(Duration.ofMinutes(30));
        assertThat(candidate.warnings())
                .containsExactly("URL ignored", "ATTACH ignored");
    }

    @Test
    void parsesAllDayRecurrenceIdentityAndSupportedRuleWithoutExpansion() {
        CalendarIcsImportDocument document = parse("""
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:series-1
                RECURRENCE-ID;VALUE=DATE:20260803
                DTSTART;VALUE=DATE:20260803
                DTEND;VALUE=DATE:20260804
                SUMMARY:Holiday
                RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=5;BYDAY=MO,WE
                END:VEVENT
                END:VCALENDAR
                """);

        CalendarIcsImportCandidate candidate = document.candidates().getFirst();
        assertThat(candidate.recurrenceId()).isEqualTo("20260803");
        assertThat(candidate.allDayStart()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(candidate.allDayEndExclusive()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(candidate.recurrenceRule())
                .isEqualTo("FREQ=WEEKLY;INTERVAL=2;COUNT=5;BYDAY=MO,WE");
        assertThat(candidate.recurrenceRuleSupported()).isTrue();
        assertThat(candidate.timed()).isFalse();
    }

    @Test
    void marksUnsupportedRecurrenceAndPositiveAlarmWithoutInventingSemantics() {
        CalendarIcsImportDocument document = parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:event-2
                DTSTART:20260731T090000Z
                DTEND:20260731T100000Z
                SUMMARY:Unsupported pieces
                RRULE:FREQ=HOURLY;BYSECOND=2
                BEGIN:VALARM
                TRIGGER:PT15M
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """);

        CalendarIcsImportCandidate candidate = document.candidates().getFirst();
        assertThat(candidate.recurrenceRuleSupported()).isFalse();
        assertThat(candidate.reminderSuggestions()).isEmpty();
        assertThat(candidate.warnings())
                .contains("RRULE unsupported", "positive VALARM trigger unsupported");
    }

    @Test
    void unfoldsLinesBeforeParsingProperties() {
        CalendarIcsImportDocument document = parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:folded-1
                DTSTART:20260731T090000Z
                DTEND:20260731T100000Z
                SUMMARY:A long
                 title
                END:VEVENT
                END:VCALENDAR
                """);

        assertThat(document.candidates().getFirst().title()).isEqualTo("A longtitle");
    }

    @Test
    void preservesFloatingLocalTimeUntilActorSelectsZone() {
        CalendarIcsImportDocument document = parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:floating-1
                DTSTART:20260731T090000
                DTEND:20260731T100000
                SUMMARY:Choose timezone
                END:VEVENT
                END:VCALENDAR
                """);

        CalendarIcsImportCandidate candidate =
                document.candidates().getFirst();
        assertThat(candidate.floating()).isTrue();
        assertThat(candidate.startsAt()).isNull();
        assertThat(candidate.zoneId()).isNull();
        assertThat(candidate.floatingStartsAt())
                .isEqualTo(java.time.LocalDateTime.of(
                        2026, 7, 31, 9, 0));
    }

    @Test
    void rejectsUnknownOrConflictingTimeZones() {
        assertThatThrownBy(() -> parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:unknown-zone
                DTSTART;TZID=Not/AZone:20260731T090000
                DTEND;TZID=Not/AZone:20260731T100000
                SUMMARY:Unknown
                END:VEVENT
                END:VCALENDAR
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE-TIME");

        assertThatThrownBy(() -> parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:conflicting-zone
                DTSTART;TZID=Asia/Taipei:20260731T090000
                DTEND;TZID=Asia/Tokyo:20260731T100000
                SUMMARY:Conflict
                END:VEVENT
                END:VCALENDAR
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    void capsAlarmSuggestionsAtEight() {
        StringBuilder alarms = new StringBuilder();
        for (int minute = 1; minute <= 9; minute++) {
            alarms.append("BEGIN:VALARM\r\nTRIGGER:-PT")
                    .append(minute)
                    .append("M\r\nEND:VALARM\r\n");
        }
        CalendarIcsImportDocument document = parse(
                "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:alarms\r\n"
                        + "DTSTART:20260731T090000Z\r\nDTEND:20260731T100000Z\r\n"
                        + "SUMMARY:Alarm cap\r\n"
                        + alarms
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n");

        CalendarIcsImportCandidate candidate = document.candidates().getFirst();
        assertThat(candidate.reminderSuggestions()).hasSize(8);
        assertThat(candidate.warnings()).contains("additional VALARM suggestions ignored");
    }

    @Test
    void rejectsMalformedUtf8AndOversizedPayload() {
        assertThatThrownBy(() -> CalendarIcsBoundedParser.parse(new byte[] {(byte) 0xc3, 0x28}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");

        assertThatThrownBy(() -> CalendarIcsBoundedParser.parse(new byte[5 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 MiB");
    }

    @Test
    void rejectsOverlongUnfoldedLineAndTooManyProperties() {
        String overlong = "SUMMARY:" + "x".repeat(8_193);
        assertThatThrownBy(() -> parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:long
                DTSTART:20260731T090000Z
                DTEND:20260731T100000Z
                %s
                END:VEVENT
                END:VCALENDAR
                """.formatted(overlong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 KiB");

        StringBuilder properties = new StringBuilder();
        for (int index = 0; index < 257; index++) {
            properties.append("X-TEST-").append(index).append(":value\r\n");
        }
        assertThatThrownBy(() -> parse(
                        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:many\r\n"
                                + properties
                                + "END:VEVENT\r\nEND:VCALENDAR\r\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 properties");
    }

    @Test
    void rejectsMoreThanTenThousandEventsAndInvalidNesting() {
        String event = "BEGIN:VEVENT\r\nUID:x\r\nDTSTART:20260731T090000Z\r\n"
                + "DTEND:20260731T100000Z\r\nSUMMARY:x\r\nEND:VEVENT\r\n";
        String payload = "BEGIN:VCALENDAR\r\n" + event.repeat(10_001) + "END:VCALENDAR\r\n";
        assertThatThrownBy(() -> parse(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000 VEVENT");

        assertThatThrownBy(() -> parse("""
                BEGIN:VCALENDAR
                BEGIN:VALARM
                TRIGGER:-PT5M
                END:VALARM
                END:VCALENDAR
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting");
    }

    private static CalendarIcsImportDocument parse(String content) {
        return CalendarIcsBoundedParser.parse(
                content.replace("\r\n", "\n")
                        .replace("\n", "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }
}

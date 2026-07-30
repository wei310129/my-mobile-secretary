package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CalendarIcsWriter {

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private CalendarIcsWriter() {}

    public static byte[] write(List<CalendarIcsEvent> events) {
        return write(events, Map.of());
    }

    public static byte[] write(
            List<CalendarIcsEvent> events,
            Map<UUID, List<Duration>> reminderOffsets) {
        if (events == null) {
            throw new IllegalArgumentException("ICS events are required");
        }
        if (reminderOffsets == null) {
            throw new IllegalArgumentException("ICS reminder offsets are required");
        }
        StringBuilder output = new StringBuilder()
                .append("BEGIN:VCALENDAR").append(CRLF)
                .append("VERSION:2.0").append(CRLF)
                .append("PRODID:-//my-mobile-secretary//Calendar v2//EN")
                .append(CRLF)
                .append("CALSCALE:GREGORIAN").append(CRLF);
        events.stream()
                .sorted(Comparator.comparing(
                                (CalendarIcsEvent event) ->
                                        event.timed()
                                                ? event.startsAt().toString()
                                                : event.allDayStart().toString())
                        .thenComparing(CalendarIcsEvent::sourceId))
                .forEach(event -> append(
                        output,
                        event,
                        reminderOffsets.getOrDefault(event.sourceId(), List.of())));
        output.append("END:VCALENDAR").append(CRLF);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(
            StringBuilder output,
            CalendarIcsEvent event,
            List<Duration> reminderOffsets) {
        output.append("BEGIN:VEVENT").append(CRLF)
                .append("UID:calendar-").append(event.sourceType()).append('-')
                .append(event.sourceId()).append("@my-mobile-secretary")
                .append(CRLF);
        if (event.timed()) {
            output.append("DTSTART;TZID=").append(event.zoneId().getId()).append(':')
                    .append(LOCAL.format(event.startsAt().atZone(event.zoneId())))
                    .append(CRLF)
                    .append("DTEND;TZID=").append(event.zoneId().getId()).append(':')
                    .append(LOCAL.format(event.endsAt().atZone(event.zoneId())))
                    .append(CRLF);
        } else {
            output.append("DTSTART;VALUE=DATE:")
                    .append(DateTimeFormatter.BASIC_ISO_DATE.format(event.allDayStart()))
                    .append(CRLF)
                    .append("DTEND;VALUE=DATE:")
                    .append(DateTimeFormatter.BASIC_ISO_DATE.format(
                            event.allDayEndExclusive()))
                    .append(CRLF);
        }
        output.append("SUMMARY:").append(escape(event.summary())).append(CRLF)
                .append("DESCRIPTION:").append(escape(event.description())).append(CRLF);
        reminderOffsets.stream().sorted().forEach(offset -> appendAlarm(output, offset));
        output.append("END:VEVENT").append(CRLF);
    }

    private static void appendAlarm(StringBuilder output, Duration offset) {
        if (offset == null
                || offset.isZero()
                || offset.isNegative()
                || offset.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("ICS alarm offset must be within 30 days");
        }
        output.append("BEGIN:VALARM").append(CRLF)
                .append("TRIGGER:-").append(offset).append(CRLF)
                .append("ACTION:DISPLAY").append(CRLF)
                .append("DESCRIPTION:Calendar reminder").append(CRLF)
                .append("END:VALARM").append(CRLF);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }
}

package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class CalendarIcsWriter {

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private CalendarIcsWriter() {}

    public static byte[] write(List<CalendarIcsEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("ICS events are required");
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
                .forEach(event -> append(output, event));
        output.append("END:VCALENDAR").append(CRLF);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder output, CalendarIcsEvent event) {
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
                .append("DESCRIPTION:").append(escape(event.description())).append(CRLF)
                .append("END:VEVENT").append(CRLF);
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

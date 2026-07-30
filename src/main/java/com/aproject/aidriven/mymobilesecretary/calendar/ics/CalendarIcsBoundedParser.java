package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CalendarIcsBoundedParser {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_EVENTS = 10_000;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_PROPERTIES_PER_EVENT = 256;
    private static final int MAX_ALARM_SUGGESTIONS = 8;
    private static final DateTimeFormatter LOCAL_DATE_TIME_SECONDS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter LOCAL_DATE_TIME_MINUTES =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");
    private static final Set<String> SUPPORTED_FREQUENCIES =
            Set.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY");
    private static final Set<String> SUPPORTED_RULE_PARTS =
            Set.of("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST");

    private CalendarIcsBoundedParser() {}

    public static CalendarIcsImportDocument parse(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("ICS payload is required");
        }
        if (payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("ICS payload exceeds 5 MiB");
        }

        String content = decodeStrictUtf8(payload);
        List<String> lines = unfold(content);
        Deque<String> components = new ArrayDeque<>();
        List<CalendarIcsImportCandidate> candidates = new ArrayList<>();
        List<String> documentWarnings = new ArrayList<>();
        EventBuilder event = null;
        AlarmBuilder alarm = null;
        String method = null;
        boolean calendarSeen = false;
        boolean calendarClosed = false;

        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            Property property = Property.parse(line);
            if ("BEGIN".equals(property.name())) {
                String component = property.value().toUpperCase(Locale.ROOT);
                validateBegin(component, components, calendarSeen, calendarClosed);
                components.push(component);
                if (components.size() > MAX_DEPTH) {
                    throw new IllegalArgumentException("ICS component depth exceeds 8");
                }
                if ("VCALENDAR".equals(component)) {
                    calendarSeen = true;
                } else if ("VEVENT".equals(component)) {
                    if (candidates.size() >= MAX_EVENTS) {
                        throw new IllegalArgumentException("ICS payload exceeds 10000 VEVENT");
                    }
                    event = new EventBuilder();
                } else if ("VALARM".equals(component)) {
                    alarm = new AlarmBuilder();
                }
                continue;
            }
            if ("END".equals(property.name())) {
                String component = property.value().toUpperCase(Locale.ROOT);
                if (components.isEmpty() || !components.peek().equals(component)) {
                    throw new IllegalArgumentException("Invalid ICS component nesting");
                }
                if ("VALARM".equals(component)) {
                    event.acceptAlarm(alarm);
                    alarm = null;
                } else if ("VEVENT".equals(component)) {
                    candidates.add(event.build());
                    event = null;
                } else if ("VCALENDAR".equals(component)) {
                    calendarClosed = true;
                }
                components.pop();
                continue;
            }
            if (components.isEmpty() || calendarClosed) {
                throw new IllegalArgumentException("ICS property outside VCALENDAR");
            }
            if (event != null) {
                event.incrementPropertyCount();
            }
            if (alarm != null) {
                alarm.accept(property);
            } else if (event != null) {
                event.accept(property);
            } else if ("VCALENDAR".equals(components.peek()) && "METHOD".equals(property.name())) {
                method = boundedText(property.value(), "METHOD");
            }
        }

        if (!calendarSeen || !calendarClosed || !components.isEmpty()) {
            throw new IllegalArgumentException("Incomplete VCALENDAR");
        }
        return new CalendarIcsImportDocument(method, candidates, documentWarnings);
    }

    private static String decodeStrictUtf8(byte[] payload) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("ICS payload must be strict UTF-8", exception);
        }
    }

    private static List<String> unfold(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("ICS contains an invalid bare carriage return");
        }
        String[] physicalLines = normalized.split("\n", -1);
        List<String> unfolded = new ArrayList<>();
        for (String physicalLine : physicalLines) {
            boolean continuation =
                    physicalLine.startsWith(" ") || physicalLine.startsWith("\t");
            if (continuation) {
                if (unfolded.isEmpty()) {
                    throw new IllegalArgumentException("Invalid folded ICS line");
                }
                int last = unfolded.size() - 1;
                String combined = unfolded.get(last) + physicalLine.substring(1);
                validateLineLength(combined);
                unfolded.set(last, combined);
            } else {
                validateLineLength(physicalLine);
                unfolded.add(physicalLine);
            }
        }
        return unfolded;
    }

    private static void validateLineLength(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("ICS unfolded line exceeds 8 KiB");
        }
    }

    private static void validateBegin(
            String component,
            Deque<String> components,
            boolean calendarSeen,
            boolean calendarClosed) {
        boolean valid = switch (component) {
            case "VCALENDAR" -> components.isEmpty() && !calendarSeen && !calendarClosed;
            case "VEVENT" -> components.size() == 1 && "VCALENDAR".equals(components.peek());
            case "VALARM" -> components.size() == 2 && "VEVENT".equals(components.peek());
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid ICS component nesting");
        }
    }

    private static String boundedText(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_LINE_BYTES) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return unescape(value);
    }

    private static String unescape(String value) {
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                char escaped = value.charAt(++index);
                output.append(switch (escaped) {
                    case 'n', 'N' -> '\n';
                    case '\\', ';', ',' -> escaped;
                    default -> escaped;
                });
            } else {
                output.append(current);
            }
        }
        return output.toString();
    }

    private static final class EventBuilder {

        private String uid;
        private String recurrenceId;
        private int sequence;
        private String title = "";
        private String description = "";
        private String location = "";
        private TemporalValue start;
        private TemporalValue end;
        private String recurrenceRule;
        private boolean recurrenceRuleSupported;
        private int propertyCount;
        private final List<Duration> reminders = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private void incrementPropertyCount() {
            propertyCount++;
            if (propertyCount > MAX_PROPERTIES_PER_EVENT) {
                throw new IllegalArgumentException("VEVENT exceeds 256 properties");
            }
        }

        private void accept(Property property) {
            switch (property.name()) {
                case "UID" -> uid = boundedText(property.value(), "UID");
                case "RECURRENCE-ID" -> recurrenceId = boundedText(property.value(), "RECURRENCE-ID");
                case "SEQUENCE" -> sequence = parseNonNegativeInteger(property.value(), "SEQUENCE");
                case "SUMMARY" -> title = boundedText(property.value(), "SUMMARY");
                case "DESCRIPTION" -> description = boundedText(property.value(), "DESCRIPTION");
                case "LOCATION" -> location = boundedText(property.value(), "LOCATION");
                case "DTSTART" -> start = TemporalValue.parse(property);
                case "DTEND" -> end = TemporalValue.parse(property);
                case "RRULE" -> {
                    recurrenceRule = property.value().toUpperCase(Locale.ROOT);
                    recurrenceRuleSupported = supportedRecurrenceRule(recurrenceRule);
                    if (!recurrenceRuleSupported) {
                        addWarning("RRULE unsupported");
                    }
                }
                case "URL" -> addWarning("URL ignored");
                case "ATTACH" -> addWarning("ATTACH ignored");
                default -> {
                    // Unknown event properties are inert.
                }
            }
        }

        private void acceptAlarm(AlarmBuilder parsedAlarm) {
            if (parsedAlarm == null || parsedAlarm.trigger == null) {
                addWarning("VALARM without relative trigger ignored");
                return;
            }
            Duration trigger;
            try {
                trigger = Duration.parse(parsedAlarm.trigger.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException exception) {
                addWarning("VALARM trigger unsupported");
                return;
            }
            if (trigger.isZero() || !trigger.isNegative()) {
                addWarning("positive VALARM trigger unsupported");
                return;
            }
            if (reminders.size() == MAX_ALARM_SUGGESTIONS) {
                addWarning("additional VALARM suggestions ignored");
                return;
            }
            reminders.add(trigger.negated());
        }

        private void addWarning(String warning) {
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
        }

        private CalendarIcsImportCandidate build() {
            if (uid == null || uid.isBlank()) {
                throw new IllegalArgumentException("VEVENT UID is required");
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException("VEVENT DTSTART and DTEND are required");
            }
            if (start.allDay != end.allDay) {
                throw new IllegalArgumentException("VEVENT start/end temporal types must match");
            }
            if (start.allDay) {
                if (!end.date.isAfter(start.date)) {
                    throw new IllegalArgumentException("VEVENT DTEND must be after DTSTART");
                }
                return new CalendarIcsImportCandidate(
                        uid,
                        recurrenceId,
                        sequence,
                        title,
                        description,
                        location,
                        null,
                        null,
                        null,
                        null,
                        null,
                        start.date,
                        end.date,
                        recurrenceRule,
                        recurrenceRuleSupported,
                        reminders,
                        warnings);
            }
            if ((start.localTime == null) != (end.localTime == null)) {
                throw new IllegalArgumentException(
                        "VEVENT start/end timezone semantics conflict");
            }
            if (start.localTime != null) {
                if (!end.localTime.isAfter(start.localTime)) {
                    throw new IllegalArgumentException(
                            "VEVENT DTEND must be after DTSTART");
                }
                return new CalendarIcsImportCandidate(
                        uid,
                        recurrenceId,
                        sequence,
                        title,
                        description,
                        location,
                        null,
                        null,
                        null,
                        start.localTime,
                        end.localTime,
                        null,
                        null,
                        recurrenceRule,
                        recurrenceRuleSupported,
                        reminders,
                        warnings);
            }
            if (!end.instant.isAfter(start.instant)
                    || !start.zoneId.equals(end.zoneId)) {
                throw new IllegalArgumentException(
                        "VEVENT end or timezone conflicts with DTSTART");
            }
            return new CalendarIcsImportCandidate(
                    uid,
                    recurrenceId,
                    sequence,
                    title,
                    description,
                    location,
                    start.instant,
                    end.instant,
                    start.zoneId,
                    null,
                    null,
                    null,
                    null,
                    recurrenceRule,
                    recurrenceRuleSupported,
                    reminders,
                    warnings);
        }
    }

    private static final class AlarmBuilder {

        private String trigger;

        private void accept(Property property) {
            if ("TRIGGER".equals(property.name())) {
                trigger = property.value();
            }
        }
    }

    private static final class TemporalValue {

        private final boolean allDay;
        private final LocalDate date;
        private final Instant instant;
        private final ZoneId zoneId;
        private final LocalDateTime localTime;

        private TemporalValue(
                boolean allDay,
                LocalDate date,
                Instant instant,
                ZoneId zoneId,
                LocalDateTime localTime) {
            this.allDay = allDay;
            this.date = date;
            this.instant = instant;
            this.zoneId = zoneId;
            this.localTime = localTime;
        }

        private static TemporalValue parse(Property property) {
            boolean dateValue = "DATE".equalsIgnoreCase(property.parameters().get("VALUE"));
            if (dateValue || property.value().matches("\\d{8}")) {
                try {
                    return new TemporalValue(
                            true,
                            LocalDate.parse(property.value(), DateTimeFormatter.BASIC_ISO_DATE),
                            null,
                            null,
                            null);
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("Invalid ICS DATE", exception);
                }
            }
            String raw = property.value().toUpperCase(Locale.ROOT);
            try {
                if (raw.endsWith("Z")) {
                    LocalDateTime local = parseLocalDateTime(raw.substring(0, raw.length() - 1));
                    return new TemporalValue(
                            false,
                            null,
                            local.toInstant(ZoneOffset.UTC),
                            ZoneOffset.UTC,
                            null);
                }
                String timeZone = property.parameters().get("TZID");
                LocalDateTime local = parseLocalDateTime(raw);
                if (timeZone == null) {
                    return new TemporalValue(
                            false, null, null, null, local);
                }
                ZoneId zone = ZoneId.of(timeZone);
                return new TemporalValue(
                        false,
                        null,
                        local.atZone(zone).toInstant(),
                        zone,
                        null);
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("Invalid ICS DATE-TIME", exception);
            }
        }

        private static LocalDateTime parseLocalDateTime(String raw) {
            DateTimeFormatter formatter =
                    raw.length() == 13 ? LOCAL_DATE_TIME_MINUTES : LOCAL_DATE_TIME_SECONDS;
            return LocalDateTime.parse(raw, formatter);
        }
    }

    private record Property(String name, Map<String, String> parameters, String value) {

        private static Property parse(String line) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Malformed ICS content line");
            }
            String[] nameAndParameters = line.substring(0, colon).split(";", -1);
            String name = nameAndParameters[0].toUpperCase(Locale.ROOT);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Malformed ICS property name");
            }
            Map<String, String> parameters = new HashMap<>();
            for (int index = 1; index < nameAndParameters.length; index++) {
                int equals = nameAndParameters[index].indexOf('=');
                if (equals <= 0) {
                    throw new IllegalArgumentException("Malformed ICS property parameter");
                }
                parameters.put(
                        nameAndParameters[index].substring(0, equals).toUpperCase(Locale.ROOT),
                        nameAndParameters[index].substring(equals + 1));
            }
            return new Property(name, Map.copyOf(parameters), line.substring(colon + 1));
        }
    }

    private static int parseNonNegativeInteger(String raw, String field) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + field, exception);
        }
    }

    private static boolean supportedRecurrenceRule(String rule) {
        Map<String, String> parts = new HashMap<>();
        for (String rawPart : rule.split(";", -1)) {
            int equals = rawPart.indexOf('=');
            if (equals <= 0 || equals == rawPart.length() - 1) {
                return false;
            }
            String name = rawPart.substring(0, equals);
            String value = rawPart.substring(equals + 1);
            if (!SUPPORTED_RULE_PARTS.contains(name) || parts.put(name, value) != null) {
                return false;
            }
        }
        if (!SUPPORTED_FREQUENCIES.contains(parts.get("FREQ"))) {
            return false;
        }
        if (!positiveInteger(parts.get("INTERVAL")) || !positiveInteger(parts.get("COUNT"))) {
            return false;
        }
        if (parts.containsKey("UNTIL") && !validUntil(parts.get("UNTIL"))) {
            return false;
        }
        if (parts.containsKey("BYDAY") && !validByDay(parts.get("BYDAY"))) {
            return false;
        }
        if (parts.containsKey("BYMONTHDAY")
                && !integerListInRange(parts.get("BYMONTHDAY"), -31, 31, true)) {
            return false;
        }
        if (parts.containsKey("BYMONTH")
                && !integerListInRange(parts.get("BYMONTH"), 1, 12, false)) {
            return false;
        }
        return !parts.containsKey("WKST")
                || parts.get("WKST").matches("MO|TU|WE|TH|FR|SA|SU");
    }

    private static boolean positiveInteger(String value) {
        if (value == null) {
            return true;
        }
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean validUntil(String value) {
        try {
            if (value.matches("\\d{8}")) {
                LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
                return true;
            }
            String local = value.endsWith("Z") ? value.substring(0, value.length() - 1) : value;
            TemporalValue.parseLocalDateTime(local);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static boolean validByDay(String value) {
        for (String item : value.split(",", -1)) {
            if (!item.matches("([+-]?[1-9]|[+-]?[1-4][0-9]|[+-]?5[0-3])?(MO|TU|WE|TH|FR|SA|SU)")) {
                return false;
            }
        }
        return true;
    }

    private static boolean integerListInRange(
            String value,
            int minimum,
            int maximum,
            boolean rejectZero) {
        Set<Integer> seen = new HashSet<>();
        for (String item : value.split(",", -1)) {
            try {
                int parsed = Integer.parseInt(item);
                if (parsed < minimum
                        || parsed > maximum
                        || (rejectZero && parsed == 0)
                        || !seen.add(parsed)) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }
}

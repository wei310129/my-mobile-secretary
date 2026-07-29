package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record CalendarRecurrenceEnd(
        Kind kind, Integer count, LocalDate untilDate, LocalDateTime untilTimed) {

    public enum Kind {
        UNBOUNDED,
        COUNT,
        UNTIL_DATE,
        UNTIL_TIMED
    }

    public CalendarRecurrenceEnd {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.COUNT && (count == null || count <= 0)) {
            throw new IllegalArgumentException("Recurrence count must be positive");
        }
    }

    public static CalendarRecurrenceEnd unbounded() {
        return new CalendarRecurrenceEnd(Kind.UNBOUNDED, null, null, null);
    }

    public static CalendarRecurrenceEnd count(int count) {
        return new CalendarRecurrenceEnd(Kind.COUNT, count, null, null);
    }

    public static CalendarRecurrenceEnd untilDate(LocalDate date) {
        return new CalendarRecurrenceEnd(
                Kind.UNTIL_DATE, null, Objects.requireNonNull(date, "date"), null);
    }

    public static CalendarRecurrenceEnd untilTimed(LocalDateTime dateTime) {
        return new CalendarRecurrenceEnd(
                Kind.UNTIL_TIMED, null, null, Objects.requireNonNull(dateTime, "dateTime"));
    }
}

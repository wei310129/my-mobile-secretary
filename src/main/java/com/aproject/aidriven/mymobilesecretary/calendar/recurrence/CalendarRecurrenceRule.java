package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

public record CalendarRecurrenceRule(
        LocalDateTime timedStart,
        Duration duration,
        ZoneId zoneId,
        LocalDate allDayStart,
        Integer daySpan,
        CalendarRecurrencePattern pattern,
        int interval,
        CalendarRecurrenceEnd end) {

    public CalendarRecurrenceRule {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(end, "end");
        if (interval <= 0) {
            throw new IllegalArgumentException("Recurrence interval must be positive");
        }
        boolean timed = timedStart != null;
        boolean allDay = allDayStart != null;
        if (timed == allDay) {
            throw new IllegalArgumentException("Rule must be exactly one of timed or all-day");
        }
        if (timed) {
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(zoneId, "zoneId");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Duration cannot be negative");
            }
            if (end.kind() == CalendarRecurrenceEnd.Kind.UNTIL_DATE) {
                throw new IllegalArgumentException("Timed recurrence requires a timed UNTIL");
            }
        } else {
            if (daySpan == null || daySpan <= 0) {
                throw new IllegalArgumentException("All-day span must be positive");
            }
            if (end.kind() == CalendarRecurrenceEnd.Kind.UNTIL_TIMED) {
                throw new IllegalArgumentException("All-day recurrence requires a date UNTIL");
            }
        }
    }

    public static CalendarRecurrenceRule timed(
            LocalDateTime start,
            Duration duration,
            ZoneId zoneId,
            CalendarRecurrencePattern pattern,
            int interval,
            CalendarRecurrenceEnd end) {
        return new CalendarRecurrenceRule(
                Objects.requireNonNull(start, "start"),
                duration,
                zoneId,
                null,
                null,
                pattern,
                interval,
                end);
    }

    public static CalendarRecurrenceRule allDay(
            LocalDate start,
            int daySpan,
            CalendarRecurrencePattern pattern,
            int interval,
            CalendarRecurrenceEnd end) {
        return new CalendarRecurrenceRule(
                null,
                null,
                null,
                Objects.requireNonNull(start, "start"),
                daySpan,
                pattern,
                interval,
                end);
    }

    public boolean timed() {
        return timedStart != null;
    }

    public LocalDate anchorDate() {
        return timed() ? timedStart.toLocalDate() : allDayStart;
    }
}

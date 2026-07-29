package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record CalendarOccurrenceKey(LocalDateTime timedStart, LocalDate allDayStart)
        implements Comparable<CalendarOccurrenceKey> {

    public CalendarOccurrenceKey {
        if ((timedStart == null) == (allDayStart == null)) {
            throw new IllegalArgumentException("Occurrence key must be exactly one of timed or all-day");
        }
    }

    public static CalendarOccurrenceKey timed(LocalDateTime start) {
        return new CalendarOccurrenceKey(Objects.requireNonNull(start, "start"), null);
    }

    public static CalendarOccurrenceKey allDay(LocalDate start) {
        return new CalendarOccurrenceKey(null, Objects.requireNonNull(start, "start"));
    }

    public boolean timed() {
        return timedStart != null;
    }

    public LocalDate date() {
        return timed() ? timedStart.toLocalDate() : allDayStart;
    }

    @Override
    public int compareTo(CalendarOccurrenceKey other) {
        int dateComparison = date().compareTo(other.date());
        if (dateComparison != 0) {
            return dateComparison;
        }
        if (timed() != other.timed()) {
            return timed() ? 1 : -1;
        }
        return timed() ? timedStart.compareTo(other.timedStart) : 0;
    }
}

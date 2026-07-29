package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.time.LocalDate;
import java.util.Objects;

public record CalendarRecurrenceWindow(
        LocalDate startInclusive, LocalDate endExclusive, int maxOccurrences) {

    public CalendarRecurrenceWindow {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException("Expansion window must be finite and ordered");
        }
        if (maxOccurrences <= 0) {
            throw new IllegalArgumentException("Caller occurrence limit must be positive");
        }
    }

    public static CalendarRecurrenceWindow between(
            LocalDate startInclusive, LocalDate endExclusive, int maxOccurrences) {
        return new CalendarRecurrenceWindow(startInclusive, endExclusive, maxOccurrences);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startInclusive) && date.isBefore(endExclusive);
    }
}

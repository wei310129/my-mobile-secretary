package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.time.Instant;

public record CalendarBusyInterval(String title, Instant startAt, Instant endAt) {

    public CalendarBusyInterval {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Busy interval title is required");
        }
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("Busy interval requires an ordered range");
        }
    }

    public boolean overlaps(Instant from, Instant to) {
        return startAt.isBefore(to) && from.isBefore(endAt);
    }
}

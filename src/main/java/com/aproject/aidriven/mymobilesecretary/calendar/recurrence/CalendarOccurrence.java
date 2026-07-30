package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.util.Objects;

public record CalendarOccurrence(
        CalendarOccurrenceKey key, CalendarPlacement placement, boolean overridden) {

    public CalendarOccurrence {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(placement, "placement");
    }
}

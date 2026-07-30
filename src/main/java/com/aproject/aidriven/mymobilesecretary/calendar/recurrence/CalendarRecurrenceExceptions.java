package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CalendarRecurrenceExceptions(
        Map<CalendarOccurrenceKey, CalendarPlacement> overrides,
        Map<CalendarOccurrenceKey, CalendarPlacement> additions,
        Set<CalendarOccurrenceKey> exclusions) {

    public CalendarRecurrenceExceptions {
        overrides = Map.copyOf(overrides);
        additions = Map.copyOf(additions);
        exclusions = Set.copyOf(exclusions);
    }

    public static CalendarRecurrenceExceptions none() {
        return new CalendarRecurrenceExceptions(Map.of(), Map.of(), Set.of());
    }

    public CalendarRecurrenceExceptions override(
            CalendarOccurrenceKey key, CalendarPlacement placement) {
        var next = new HashMap<>(overrides);
        next.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(placement, "placement"));
        return new CalendarRecurrenceExceptions(next, additions, exclusions);
    }

    public CalendarRecurrenceExceptions add(
            CalendarOccurrenceKey key, CalendarPlacement placement) {
        var next = new HashMap<>(additions);
        next.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(placement, "placement"));
        return new CalendarRecurrenceExceptions(overrides, next, exclusions);
    }

    public CalendarRecurrenceExceptions exclude(CalendarOccurrenceKey key) {
        var next = new HashSet<>(exclusions);
        next.add(Objects.requireNonNull(key, "key"));
        return new CalendarRecurrenceExceptions(overrides, additions, next);
    }
}

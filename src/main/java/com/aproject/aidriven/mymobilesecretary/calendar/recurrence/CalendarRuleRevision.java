package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import java.util.Objects;
import java.util.UUID;

public record CalendarRuleRevision(
        UUID seriesId,
        int revision,
        CalendarRecurrenceRule rule,
        CalendarOccurrenceKey effectiveFrom,
        CalendarOccurrenceKey effectiveUntilExclusive,
        Integer parentRevision,
        CalendarOccurrenceKey splitFromKey,
        CalendarRuleRevisionState state) {

    public CalendarRuleRevision {
        Objects.requireNonNull(seriesId, "seriesId");
        if (revision <= 0) {
            throw new IllegalArgumentException("Revision must be positive");
        }
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(state, "state");
    }

    CalendarRuleRevision endAt(
            CalendarOccurrenceKey boundary, CalendarRuleRevisionState nextState) {
        return new CalendarRuleRevision(
                seriesId,
                revision,
                rule,
                effectiveFrom,
                boundary,
                parentRevision,
                splitFromKey,
                nextState);
    }
}

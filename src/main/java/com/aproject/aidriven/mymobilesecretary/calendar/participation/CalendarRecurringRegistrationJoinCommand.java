package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarOccurrenceKey;
import java.util.UUID;

public record CalendarRecurringRegistrationJoinCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope target,
        UUID seriesId,
        int recurrenceRuleRevision,
        CalendarOccurrenceKey occurrenceKey) {}

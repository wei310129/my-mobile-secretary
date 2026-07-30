package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRecurringRegistrationPolicyCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope target,
        UUID seriesId,
        int recurrenceRuleRevision,
        CalendarRecurringRegistrationScope registrationScope) {}

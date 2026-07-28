package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRegistrationDecisionView(
        UUID registrationId,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarRegistrationState state,
        long revision,
        boolean overrideConfirmationRequired,
        String warning,
        int committedCount,
        int overCapacityCount) {}

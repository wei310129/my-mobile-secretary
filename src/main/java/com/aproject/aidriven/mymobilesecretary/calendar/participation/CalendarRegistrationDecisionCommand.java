package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRegistrationDecisionCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        UUID registrationId,
        Decision decision,
        String reason,
        long expectedRevision,
        boolean overrideConfirmed) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}

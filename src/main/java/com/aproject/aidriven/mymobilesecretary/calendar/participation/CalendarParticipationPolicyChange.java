package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarParticipationPolicyChange(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarParticipationPolicy policy,
        long expectedRevision) {}

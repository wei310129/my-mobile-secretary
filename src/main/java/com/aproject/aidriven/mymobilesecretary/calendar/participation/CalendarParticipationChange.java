package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarParticipationChange(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarParticipationStatus status,
        long expectedRevision) {}

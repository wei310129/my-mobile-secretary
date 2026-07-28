package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarOrganizerRemovalCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        UUID participantUserId,
        long expectedRevision,
        String reason) {}

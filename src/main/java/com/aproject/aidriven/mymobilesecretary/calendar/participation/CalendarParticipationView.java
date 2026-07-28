package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarParticipationView(
        UUID id,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarParticipationStatus status,
        long revision,
        CalendarParticipationPropagationState propagationState,
        boolean awaitingAcknowledgement) {}

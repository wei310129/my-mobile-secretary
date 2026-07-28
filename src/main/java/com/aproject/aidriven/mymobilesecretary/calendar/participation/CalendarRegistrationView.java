package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRegistrationView(
        UUID id,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarRegistrationState state,
        long revision,
        Long waitlistPosition) {}

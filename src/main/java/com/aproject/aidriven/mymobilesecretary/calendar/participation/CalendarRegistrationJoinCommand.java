package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRegistrationJoinCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope) {}

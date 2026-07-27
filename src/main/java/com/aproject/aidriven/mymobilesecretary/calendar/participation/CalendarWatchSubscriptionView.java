package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarWatchSubscriptionView(
        UUID id,
        UUID planId,
        CalendarParticipationScope scope,
        boolean active,
        long revision) {}

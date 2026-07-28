package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarWatchSubscriptionChange(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        long expectedRevision) {}

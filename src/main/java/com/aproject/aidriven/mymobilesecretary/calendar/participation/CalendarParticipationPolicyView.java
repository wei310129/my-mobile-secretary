package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarParticipationPolicyView(
        UUID id,
        UUID planId,
        CalendarParticipationScope scope,
        CalendarParticipationPolicy policy,
        long revision) {}

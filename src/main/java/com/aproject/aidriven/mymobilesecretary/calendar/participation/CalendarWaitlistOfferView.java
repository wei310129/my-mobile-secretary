package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.time.Instant;
import java.util.UUID;

public record CalendarWaitlistOfferView(
        UUID id,
        UUID planId,
        CalendarParticipationScope scope,
        Instant expiresAt,
        String status,
        long revision) {}

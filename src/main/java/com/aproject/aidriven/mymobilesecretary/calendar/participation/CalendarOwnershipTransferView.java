package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.time.Instant;
import java.util.UUID;

public record CalendarOwnershipTransferView(
        UUID id,
        UUID planId,
        UUID fromOwnerUserId,
        UUID toOwnerUserId,
        CalendarOwnershipTransferStatus status,
        long expectedOwnershipRevision,
        long revision,
        Instant expiresAt) {}

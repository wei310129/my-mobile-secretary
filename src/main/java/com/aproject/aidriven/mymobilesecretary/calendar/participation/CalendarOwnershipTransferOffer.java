package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.time.Duration;
import java.util.UUID;

public record CalendarOwnershipTransferOffer(
        String requestId,
        UUID planId,
        UUID targetOwnerUserId,
        long expectedOwnershipRevision,
        Duration timeToLive) {}

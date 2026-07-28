package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.time.Instant;
import java.util.UUID;

public record CalendarOwnershipTransferLifecycleEvent(
        UUID transferId,
        UUID planId,
        Action action,
        Instant occurredAt) {

    public enum Action {
        OFFERED,
        ACCEPTED,
        CANCELED
    }
}

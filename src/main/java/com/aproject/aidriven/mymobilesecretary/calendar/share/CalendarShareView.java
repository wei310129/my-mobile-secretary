package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarShareView(
        UUID id,
        UUID planId,
        UUID granteeUserId,
        Status status,
        long revision) {

    public enum Status {
        ACTIVE,
        REVOKED
    }
}

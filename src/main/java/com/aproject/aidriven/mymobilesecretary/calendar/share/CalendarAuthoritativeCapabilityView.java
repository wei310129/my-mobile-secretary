package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarAuthoritativeCapabilityView(
        UUID id,
        UUID shareId,
        UUID planId,
        UUID nodeId,
        CalendarAuthoritativeCapabilityGrant.Scope scope,
        Status status,
        long revision) {

    public enum Status {
        ACTIVE,
        REVOKED
    }
}

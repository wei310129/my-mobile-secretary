package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import java.util.Objects;
import java.util.UUID;

public record CalendarAuthoritativeLocationChange(
        String requestKey,
        UUID capabilityId,
        UUID nodeId,
        CalendarLocation location,
        long expectedNodeRevision,
        String reason,
        String source) {

    public CalendarAuthoritativeLocationChange {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(location, "location");
        reason = CalendarAuthoritativeTimeChange.bounded(
                reason, 500, "reason");
        source = CalendarAuthoritativeTimeChange.bounded(
                source, 80, "source");
        if (expectedNodeRevision < 1) {
            throw new IllegalArgumentException(
                    "Expected node revision must be positive");
        }
    }
}

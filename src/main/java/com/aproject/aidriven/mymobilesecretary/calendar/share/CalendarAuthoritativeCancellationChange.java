package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Objects;
import java.util.UUID;

public record CalendarAuthoritativeCancellationChange(
        String requestKey,
        UUID capabilityId,
        UUID nodeId,
        long expectedNodeRevision,
        String reason,
        String source) {

    public CalendarAuthoritativeCancellationChange {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(nodeId, "nodeId");
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

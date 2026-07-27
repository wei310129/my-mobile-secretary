package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CalendarAuthoritativeTimeChange(
        String requestKey,
        UUID capabilityId,
        UUID nodeId,
        Instant absoluteTime,
        long expectedNodeRevision,
        String reason,
        String source) {

    public CalendarAuthoritativeTimeChange {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(absoluteTime, "absoluteTime");
        reason = bounded(reason, 500, "reason");
        source = bounded(source, 80, "source");
        if (expectedNodeRevision < 1) {
            throw new IllegalArgumentException(
                    "Expected node revision must be positive");
        }
    }

    static String bounded(String value, int limit, String field) {
        if (value == null
                || value.isBlank()
                || value.strip().length() > limit) {
            throw new IllegalArgumentException(
                    "A bounded authoritative " + field + " is required");
        }
        return value.strip();
    }
}

package com.aproject.internal.aidispatcher.domain;

import java.time.Instant;
import java.util.Objects;

public record PendingEventWindow(
        Instant firstRecordedAt,
        Instant lastRecordedAt,
        long eventCount
) {

    public PendingEventWindow {
        Objects.requireNonNull(firstRecordedAt, "firstRecordedAt");
        Objects.requireNonNull(lastRecordedAt, "lastRecordedAt");
        if (lastRecordedAt.isBefore(firstRecordedAt)) {
            throw new IllegalArgumentException("lastRecordedAt must not be before firstRecordedAt");
        }
        if (eventCount <= 0) {
            throw new IllegalArgumentException("eventCount must be positive");
        }
    }
}

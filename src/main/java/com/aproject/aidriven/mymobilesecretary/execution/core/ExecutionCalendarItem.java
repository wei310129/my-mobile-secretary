package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ExecutionCalendarItem(
        String stableId,
        String title,
        Instant startsAt,
        Instant endsAt,
        boolean allDay,
        boolean fixed,
        boolean actorAdopted,
        Instant explicitPrepStartsAt,
        Duration prepBuffer) {

    public ExecutionCalendarItem {
        stableId = requireText(stableId, "stableId");
        title = requireText(title, "title");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        if (explicitPrepStartsAt != null && explicitPrepStartsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException(
                    "explicitPrepStartsAt must not be after startsAt");
        }
        if (prepBuffer != null && prepBuffer.isNegative()) {
            throw new IllegalArgumentException("prepBuffer must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}

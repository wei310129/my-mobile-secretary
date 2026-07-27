package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Objects;
import java.util.UUID;

public record CalendarEditorActivityChange(
        String requestKey,
        UUID shareId,
        UUID activityId,
        Field field,
        String value,
        long expectedVersion) {

    public CalendarEditorActivityChange {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(field, "field");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "A calendar activity value is required");
        }
        value = value.strip();
        int limit = field == Field.TITLE ? 200 : 80;
        if (value.length() > limit) {
            throw new IllegalArgumentException(
                    "Calendar activity value exceeds its limit");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "Expected activity version cannot be negative");
        }
    }

    public enum Field {
        TITLE,
        CATEGORY
    }
}

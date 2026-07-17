package com.aproject.internal.aidispatcher.trigger.domain;

import java.util.List;

public record TriggerEventPage(
        String sourceKey,
        String expectedCursor,
        String nextCursor,
        List<DevelopmentTriggerEvent> events
) {

    public TriggerEventPage {
        sourceKey = requireText(sourceKey, "sourceKey", 100);
        expectedCursor = optionalText(expectedCursor, "expectedCursor");
        nextCursor = optionalText(nextCursor, "nextCursor");
        events = events == null ? List.of() : List.copyOf(events);
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return stripped;
    }

    private static String optionalText(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be null or non-blank");
        }
        return value.strip();
    }
}

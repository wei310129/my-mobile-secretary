package com.aproject.internal.aidispatcher.trigger.domain;

import java.time.Instant;
import java.util.Objects;

public record DevelopmentTriggerEvent(
        String sourceEventId,
        String triggerType,
        String subjectRef,
        int schemaVersion,
        Instant occurredAt,
        String metadataJson
) {

    public DevelopmentTriggerEvent {
        sourceEventId = requireText(sourceEventId, "sourceEventId", 200);
        triggerType = requireText(triggerType, "triggerType", 100);
        subjectRef = requireText(subjectRef, "subjectRef", 500);
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
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
}

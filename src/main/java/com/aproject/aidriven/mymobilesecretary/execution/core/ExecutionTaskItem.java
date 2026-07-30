package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.time.Instant;
import java.util.Objects;

public record ExecutionTaskItem(
        String stableId,
        String title,
        Instant dueAt,
        ExecutionPriority priority,
        boolean open,
        boolean dependencyBlocked) {

    public ExecutionTaskItem {
        stableId = requireText(stableId, "stableId");
        title = requireText(title, "title");
        Objects.requireNonNull(priority, "priority");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}

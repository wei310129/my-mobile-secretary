package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.time.Instant;
import java.util.Objects;

public record ExecutionRecommendationItem(
        ExecutionItemKind kind,
        String stableId,
        String title,
        Instant effectiveAt,
        boolean notUrgent) {

    public ExecutionRecommendationItem {
        Objects.requireNonNull(kind, "kind");
        stableId = requireText(stableId, "stableId");
        title = requireText(title, "title");
    }

    public ExecutionRecommendationItem asNotUrgent() {
        return new ExecutionRecommendationItem(kind, stableId, title, effectiveAt, true);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}

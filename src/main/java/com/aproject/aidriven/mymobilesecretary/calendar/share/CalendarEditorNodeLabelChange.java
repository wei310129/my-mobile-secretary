package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Objects;
import java.util.UUID;

public record CalendarEditorNodeLabelChange(
        String requestKey,
        UUID shareId,
        UUID nodeId,
        String label,
        long expectedNodeRevision) {

    public CalendarEditorNodeLabelChange {
        requestKey = CalendarShareService.requireKey(requestKey);
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(nodeId, "nodeId");
        if (label == null
                || label.isBlank()
                || label.strip().length() > 200) {
            throw new IllegalArgumentException(
                    "A bounded calendar node label is required");
        }
        label = label.strip();
        if (expectedNodeRevision < 1) {
            throw new IllegalArgumentException(
                    "Expected node revision must be positive");
        }
    }
}

package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.time.Instant;
import java.util.UUID;

public record CalendarKnowledgeBindingView(
        UUID id,
        SourceKind sourceKind,
        long sourceId,
        CalendarKnowledgeTarget.TargetKind targetKind,
        UUID planId,
        Instant sourceUpdatedAt,
        ReviewState reviewState,
        Status status,
        long revision) {

    public enum SourceKind {
        ANNOTATION,
        FACT
    }

    public enum ReviewState {
        CURRENT,
        REVIEW_REQUIRED
    }

    public enum Status {
        ACTIVE,
        ARCHIVED
    }
}

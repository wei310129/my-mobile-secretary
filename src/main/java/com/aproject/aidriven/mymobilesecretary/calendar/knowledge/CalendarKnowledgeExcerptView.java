package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.util.UUID;

public record CalendarKnowledgeExcerptView(
        UUID id,
        SourceKind sourceKind,
        UUID bindingId,
        UUID planId,
        int versionNumber,
        String snapshotTitle,
        String snapshotText,
        Status status,
        long revision) {

    public enum SourceKind {
        FACT,
        ANNOTATION
    }

    public enum Status {
        DRAFT,
        APPROVED,
        REVIEW_REQUIRED,
        REVOKED
    }
}

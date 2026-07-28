package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.time.Instant;

public record CalendarKnowledgeEvidenceView(
        SourceKind sourceKind,
        String title,
        String content,
        Instant updatedAt) {

    public enum SourceKind {
        FACT,
        ANNOTATION
    }
}

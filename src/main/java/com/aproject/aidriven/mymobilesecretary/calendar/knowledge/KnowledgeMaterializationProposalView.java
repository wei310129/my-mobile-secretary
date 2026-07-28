package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.util.UUID;

public record KnowledgeMaterializationProposalView(
        UUID id,
        CalendarKnowledgeBindingView.SourceKind sourceKind,
        UUID sourceBindingId,
        KnowledgeMaterializationView.TargetKind targetKind,
        Status status,
        long revision,
        java.time.Instant expiresAt) {

    public enum Status {
        PENDING_CONFIRMATION,
        COMPLETED,
        CANCELED,
        EXPIRED
    }
}

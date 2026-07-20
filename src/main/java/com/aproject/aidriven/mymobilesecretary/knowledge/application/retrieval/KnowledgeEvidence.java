package com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Untrusted data returned by retrieval; it is evidence and never an executable instruction. */
public record KnowledgeEvidence(
        String evidenceId,
        KnowledgeSourceType sourceType,
        String sourceId,
        String title,
        String content,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Double relevanceScore,
        String sourceVersion,
        UUID workspaceId,
        UUID ownerUserId,
        boolean structured
) {
    public KnowledgeEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

package com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One bounded, actor-scoped request for non-transactional personal knowledge. */
public record KnowledgeQuery(
        UUID workspaceId,
        UUID actorUserId,
        String queryText,
        Set<KnowledgeCategory> categories,
        Set<KnowledgeSourceType> sourceTypes,
        int limit,
        boolean includeSharedWorkspaceKnowledge,
        Instant createdFrom,
        Instant createdTo,
        Map<String, String> metadataFilter
) {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

    public KnowledgeQuery {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        queryText = queryText == null ? "" : queryText.strip();
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        sourceTypes = sourceTypes == null ? Set.of() : Set.copyOf(sourceTypes);
        limit = Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        metadataFilter = metadataFilter == null ? Map.of() : Map.copyOf(metadataFilter);
        if (createdFrom != null && createdTo != null && !createdTo.isAfter(createdFrom)) {
            throw new IllegalArgumentException("knowledge query time range is invalid");
        }
    }
}

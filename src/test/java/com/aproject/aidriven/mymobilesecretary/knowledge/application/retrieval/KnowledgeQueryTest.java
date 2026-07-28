package com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeQueryTest {

    @Test
    void appliesDefaultsAndCapsTheLimit() {
        UUID workspaceId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        KnowledgeQuery defaulted = new KnowledgeQuery(
                workspaceId, actorId, null, null, null, 0,
                false, null, null, null);
        KnowledgeQuery capped = new KnowledgeQuery(
                workspaceId, actorId, "manual", Set.of(), Set.of(), 500,
                false, null, null, Map.of());

        assertThat(defaulted.queryText()).isEmpty();
        assertThat(defaulted.limit()).isEqualTo(KnowledgeQuery.DEFAULT_LIMIT);
        assertThat(defaulted.categories()).isEmpty();
        assertThat(capped.limit()).isEqualTo(KnowledgeQuery.MAX_LIMIT);
    }

    @Test
    void rejectsAnInvalidTimeRange() {
        Instant at = Instant.parse("2026-07-20T00:00:00Z");

        assertThatThrownBy(() -> new KnowledgeQuery(
                UUID.randomUUID(), UUID.randomUUID(), "manual", Set.of(), Set.of(), 5,
                false, at, at, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time range");
    }
}

package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductObservationDraftTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void requiresAtLeastOneVisibleProductFact() {
        assertThatThrownBy(() -> ProductObservationDraft.create(
                null, null, null, null, NOW, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsVisibleFactsAndResolvesOnlyAfterUserChoice() {
        var draft = ProductObservationDraft.create("水泥漆", "得利", "百合白",
                "油漆標籤", NOW, NOW.plusSeconds(60));
        assertThat(draft.displayName()).isEqualTo("得利 水泥漆 百合白");
        assertThat(draft.getStatus()).isEqualTo(ProductObservationDraft.Status.PENDING_PURPOSE);
        draft.resolve(NOW.plusSeconds(1));
        assertThat(draft.getStatus()).isEqualTo(ProductObservationDraft.Status.RESOLVED);
    }
}

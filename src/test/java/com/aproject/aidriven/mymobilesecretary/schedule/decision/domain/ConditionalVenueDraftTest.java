package com.aproject.aidriven.mymobilesecretary.schedule.decision.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConditionalVenueDraftTest {

    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final Instant DECISION = Instant.parse("2026-07-20T10:00:00Z");
    private static final Instant START = Instant.parse("2026-07-20T12:00:00Z");
    private static final Instant END = Instant.parse("2026-07-20T13:00:00Z");

    @Test
    void resolvesOnlyOneOfTheStoredPlaces() {
        ConditionalVenueDraft draft = ConditionalVenueDraft.create(
                "運動", START, END, "健身房", "家", DECISION, 11L, NOW);

        draft.resolve("家", 22L, NOW.plusSeconds(60));

        assertThat(draft.getStatus()).isEqualTo(ConditionalVenueDraft.Status.RESOLVED);
        assertThat(draft.getSelectedPlaceName()).isEqualTo("家");
        assertThat(draft.getScheduleItemId()).isEqualTo(22L);
        assertThatThrownBy(() -> draft.resolve("健身房", 23L, NOW.plusSeconds(120)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAPlaceThatWasNotAnOption() {
        ConditionalVenueDraft draft = ConditionalVenueDraft.create(
                "運動", START, END, "健身房", "家", DECISION, 11L, NOW);

        assertThatThrownBy(() -> draft.resolve("公園", 22L, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

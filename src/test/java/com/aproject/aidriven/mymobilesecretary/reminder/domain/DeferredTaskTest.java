package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeferredTaskTest {

    private static final Instant NOW = Instant.parse("2026-07-18T13:00:00Z");

    @Test
    void waitingDefinitionTransitionsOnceToTriggered() {
        DeferredTask deferred = DeferredTask.waitFor(
                7L, "回家前加油", null, TaskPriority.NORMAL, 0, NOW);

        deferred.markTriggered(9L, NOW.plusSeconds(60));

        assertThat(deferred.getStatus()).isEqualTo(DeferredTask.Status.TRIGGERED);
        assertThat(deferred.getCreatedTaskId()).isEqualTo(9L);
        assertThat(deferred.getTriggeredAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> deferred.markTriggered(10L, NOW.plusSeconds(120)))
                .hasMessageContaining("cannot trigger");
    }

    @Test
    void negativeOrExcessiveOffsetIsRejected() {
        assertThatThrownBy(() -> DeferredTask.waitFor(
                7L, "加油", null, TaskPriority.NORMAL, -1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeferredTask.waitFor(
                7L, "加油", null, TaskPriority.NORMAL, 10_081, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

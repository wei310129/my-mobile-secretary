package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReminderQueuePropertiesTest {

    @Test
    void acceptsBoundedLeaseRetryAndBatchPolicy() {
        ReminderQueueProperties properties = new ReminderQueueProperties(
                Duration.ofMinutes(2), Duration.ofSeconds(30), 5, 100);

        assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.maxBatch()).isEqualTo(100);
    }

    @Test
    void rejectsUnboundedOrNonPositiveValues() {
        assertThatThrownBy(() -> new ReminderQueueProperties(
                Duration.ZERO, Duration.ofSeconds(1), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReminderQueueProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReminderQueueProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

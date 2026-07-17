package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationOutboxPropertiesTest {

    @Test
    void rejectsUnsafeLeaseAndRetryBounds() {
        assertThatThrownBy(() -> new NotificationOutboxProperties(
                Duration.ZERO, Duration.ofSeconds(1), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationOutboxProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationOutboxProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

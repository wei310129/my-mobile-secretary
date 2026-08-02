package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdaptiveDepartureReminderPolicyTest {

    @Test
    void appliesApprovedDurationBoundariesExactly() {
        assertThat(AdaptiveDepartureReminderPolicy.offsets(Duration.ZERO))
                .containsExactly(Duration.ofMinutes(-10), Duration.ZERO);
        assertThat(AdaptiveDepartureReminderPolicy.offsets(Duration.ofMinutes(30)))
                .containsExactly(Duration.ofMinutes(-10), Duration.ZERO);
        assertThat(AdaptiveDepartureReminderPolicy.offsets(Duration.ofMinutes(31)))
                .containsExactly(Duration.ofMinutes(-30), Duration.ZERO);
        assertThat(AdaptiveDepartureReminderPolicy.offsets(Duration.ofMinutes(90)))
                .containsExactly(Duration.ofMinutes(-30), Duration.ZERO);
        assertThat(AdaptiveDepartureReminderPolicy.offsets(Duration.ofMinutes(91)))
                .containsExactly(
                        Duration.ofMinutes(-60), Duration.ofMinutes(-20), Duration.ZERO);
    }

    @Test
    void negativeOrMissingDurationIsRejected() {
        assertThatThrownBy(() -> AdaptiveDepartureReminderPolicy.offsets(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdaptiveDepartureReminderPolicy.offsets(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

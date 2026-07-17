package com.aproject.internal.aidispatcher.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DispatcherPropertiesTest {

    @Test
    void rejectsMaximumWaitShorterThanQuietPeriod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(4)));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(30)));
    }

    @Test
    void rejectsNonPositiveRunLimits() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                0, 65_536));
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                20, 0));
    }

    @Test
    void rejectsRunLimitsAboveTheOperationalCeiling() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                101, 65_536));
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                20, 1_048_577));
    }
}

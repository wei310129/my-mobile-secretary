package com.aproject.internal.aidispatcher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class DispatcherPropertiesTest {

    @Test
    void rejectsMaximumWaitShorterThanQuietPeriod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(4),
                20, 65_536));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispatcherProperties(
                false, Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(30),
                20, 65_536));
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

    @Test
    void bindsTheCanonicalRecordConstructorFromSpringConfiguration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "ai-dispatcher.enabled", "false",
                "ai-dispatcher.poll-interval", "1s",
                "ai-dispatcher.quiet-period", "5m",
                "ai-dispatcher.maximum-wait", "30m",
                "ai-dispatcher.max-events-per-run", "20",
                "ai-dispatcher.max-event-payload-bytes-per-run", "65536"));

        DispatcherProperties properties = new Binder(source)
                .bind("ai-dispatcher", Bindable.of(DispatcherProperties.class))
                .get();

        assertThat(properties.maxEventsPerRun()).isEqualTo(20);
        assertThat(properties.maxEventPayloadBytesPerRun()).isEqualTo(65_536);
    }
}

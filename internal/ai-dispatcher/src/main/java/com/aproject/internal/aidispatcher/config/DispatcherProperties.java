package com.aproject.internal.aidispatcher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher")
public record DispatcherProperties(
        boolean enabled,
        Duration pollInterval,
        Duration quietPeriod,
        Duration maximumWait,
        int maxEventsPerRun,
        int maxEventPayloadBytesPerRun
) {

    public DispatcherProperties(boolean enabled,
                                Duration pollInterval,
                                Duration quietPeriod,
                                Duration maximumWait) {
        this(enabled, pollInterval, quietPeriod, maximumWait, 20, 65_536);
    }

    public DispatcherProperties {
        requirePositive(pollInterval, "pollInterval");
        requirePositive(quietPeriod, "quietPeriod");
        requirePositive(maximumWait, "maximumWait");
        requirePositive(maxEventsPerRun, "maxEventsPerRun");
        requirePositive(maxEventPayloadBytesPerRun, "maxEventPayloadBytesPerRun");
        if (maximumWait.compareTo(quietPeriod) < 0) {
            throw new IllegalArgumentException("maximumWait must not be shorter than quietPeriod");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

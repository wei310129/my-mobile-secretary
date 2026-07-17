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

    public DispatcherProperties {
        requirePositive(pollInterval, "pollInterval");
        requirePositive(quietPeriod, "quietPeriod");
        requirePositive(maximumWait, "maximumWait");
        requireRange(maxEventsPerRun, 1, 100, "maxEventsPerRun");
        requireRange(maxEventPayloadBytesPerRun, 4096, 1_048_576,
                "maxEventPayloadBytesPerRun");
        if (maximumWait.compareTo(quietPeriod) < 0) {
            throw new IllegalArgumentException("maximumWait must not be shorter than quietPeriod");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}

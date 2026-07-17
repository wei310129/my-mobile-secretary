package com.aproject.internal.aidispatcher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.retention")
public record DispatcherRetentionProperties(
        Duration consumedPayloadRetention,
        Duration sweepInterval
) {
    public DispatcherRetentionProperties {
        requirePositive(consumedPayloadRetention, "consumedPayloadRetention");
        requirePositive(sweepInterval, "sweepInterval");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

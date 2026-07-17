package com.aproject.internal.aidispatcher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.codex")
public record CodexLifecycleProperties(
        Duration heartbeatTimeout,
        Duration retryDelay,
        int maxRecoveryAttempts
) {

    public CodexLifecycleProperties {
        requirePositive(heartbeatTimeout, "heartbeatTimeout");
        requirePositive(retryDelay, "retryDelay");
        if (maxRecoveryAttempts <= 0) {
            throw new IllegalArgumentException("maxRecoveryAttempts must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

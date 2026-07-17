package com.aproject.aidriven.mymobilesecretary.integration.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.notification.outbox")
public record NotificationOutboxProperties(
        @DefaultValue("2m") Duration lease,
        @DefaultValue("30s") Duration retryDelay,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("100") int maxBatch
) {
    public NotificationOutboxProperties {
        requirePositive(lease, "lease");
        requirePositive(retryDelay, "retryDelay");
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        if (maxBatch < 1 || maxBatch > 1000) {
            throw new IllegalArgumentException("maxBatch must be between 1 and 1000");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

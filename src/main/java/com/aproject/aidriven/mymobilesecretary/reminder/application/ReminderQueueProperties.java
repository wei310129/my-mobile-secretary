package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Reliable reminder queue lease and retry policy. */
@ConfigurationProperties(prefix = "app.reminder.queue")
public record ReminderQueueProperties(
        @DefaultValue("2m") Duration lease,
        @DefaultValue("30s") Duration retryDelay,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("100") int maxBatch
) {
    public ReminderQueueProperties {
        requirePositiveMillis(lease, "lease");
        requirePositiveMillis(retryDelay, "retryDelay");
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        if (maxBatch < 1 || maxBatch > 1000) {
            throw new IllegalArgumentException("maxBatch must be between 1 and 1000");
        }
    }

    private static void requirePositiveMillis(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero() || value.toMillis() < 1L) {
            throw new IllegalArgumentException(field + " must be at least 1ms");
        }
    }
}

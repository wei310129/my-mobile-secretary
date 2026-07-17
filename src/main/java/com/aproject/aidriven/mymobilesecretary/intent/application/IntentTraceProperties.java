package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Privacy and retention settings for AI intent decision traces. */
@ConfigurationProperties(prefix = "app.intent.trace")
public record IntentTraceProperties(
        String encryptionKey,
        @DefaultValue("intent-trace-v1") String encryptionKeyId,
        @DefaultValue("168h") Duration rawRetention,
        @DefaultValue("2160h") Duration summaryRetention
) {

    public IntentTraceProperties {
        if (rawRetention == null || rawRetention.isNegative() || rawRetention.isZero()) {
            throw new IllegalArgumentException("Intent trace raw retention must be positive");
        }
        if (summaryRetention == null || summaryRetention.isNegative() || summaryRetention.isZero()) {
            throw new IllegalArgumentException("Intent trace summary retention must be positive");
        }
        if (rawRetention.compareTo(summaryRetention) > 0) {
            throw new IllegalArgumentException("Intent trace raw retention cannot exceed summary retention");
        }
    }
}

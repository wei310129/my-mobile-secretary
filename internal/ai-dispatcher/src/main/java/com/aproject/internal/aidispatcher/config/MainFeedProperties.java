package com.aproject.internal.aidispatcher.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.main-feed")
public record MainFeedProperties(
        boolean enabled,
        URI baseUrl,
        String bearerToken,
        Duration connectTimeout,
        Duration readTimeout
) {
    public MainFeedProperties {
        if (baseUrl == null || !baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("main feed baseUrl must be absolute");
        }
        bearerToken = bearerToken == null ? "" : bearerToken.strip();
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

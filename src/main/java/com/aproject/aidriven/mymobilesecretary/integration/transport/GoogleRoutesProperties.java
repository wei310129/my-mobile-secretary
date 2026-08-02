package com.aproject.aidriven.mymobilesecretary.integration.transport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Google Routes API 設定；api key 只能由 secrets.yaml 或環境提供。 */
@ConfigurationProperties(prefix = "app.integration.google-routes")
public record GoogleRoutesProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://routes.googleapis.com") String baseUrl,
        @DefaultValue("5s") Duration timeout) {

    public boolean usable() {
        return enabled && !apiKey.isBlank();
    }
}

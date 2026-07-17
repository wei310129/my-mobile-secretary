package com.aproject.aidriven.mymobilesecretary.account.security.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.security.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration retention
) {
    public IdempotencyProperties {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Idempotency retention must be positive");
        }
    }
}

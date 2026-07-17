package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.integration.line.message-history")
public record LineMessageRetentionProperties(
        @DefaultValue("90d") Duration retention
) {
    public LineMessageRetentionProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("LINE message retention must be positive");
        }
    }
}

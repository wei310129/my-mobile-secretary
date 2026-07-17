package com.aproject.aidriven.mymobilesecretary.account.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.security.audit")
public record SecurityAuditProperties(
        @DefaultValue("365d") Duration retention
) {
    public SecurityAuditProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("app.security.audit.retention must be positive");
        }
    }
}

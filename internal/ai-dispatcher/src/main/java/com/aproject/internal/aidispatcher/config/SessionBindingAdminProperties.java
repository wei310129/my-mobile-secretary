package com.aproject.internal.aidispatcher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.session-binding-api")
public record SessionBindingAdminProperties(
        boolean enabled,
        String adminToken
) {

    public SessionBindingAdminProperties {
        adminToken = adminToken == null ? "" : adminToken.strip();
        if (enabled && adminToken.length() < 32) {
            throw new IllegalArgumentException(
                    "session binding adminToken must contain at least 32 characters when enabled");
        }
        if (adminToken.length() > 512) {
            throw new IllegalArgumentException(
                    "session binding adminToken must not exceed 512 characters");
        }
    }
}

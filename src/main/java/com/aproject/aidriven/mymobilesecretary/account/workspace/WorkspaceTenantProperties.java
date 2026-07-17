package com.aproject.aidriven.mymobilesecretary.account.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.workspace")
public record WorkspaceTenantProperties(boolean legacyFallbackEnabled) {
}

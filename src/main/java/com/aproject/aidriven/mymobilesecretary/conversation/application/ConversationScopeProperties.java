package com.aproject.aidriven.mymobilesecretary.conversation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime-only HMAC key material. Values are supplied from environment or untracked secrets. */
@ConfigurationProperties("app.conversation.scope")
public record ConversationScopeProperties(
        int currentKeyVersion,
        String currentHmacKeyBase64,
        Integer previousKeyVersion,
        String previousHmacKeyBase64) {

    public ConversationScopeProperties {
        if (currentKeyVersion < 1) {
            throw new IllegalArgumentException("current scope key version must be positive");
        }
        if (currentHmacKeyBase64 == null || currentHmacKeyBase64.isBlank()) {
            throw new IllegalArgumentException("current scope HMAC key is required");
        }
        if ((previousKeyVersion == null) != (previousHmacKeyBase64 == null
                || previousHmacKeyBase64.isBlank())) {
            throw new IllegalArgumentException("previous scope key version and key must be configured together");
        }
        if (previousKeyVersion != null && previousKeyVersion < 1) {
            throw new IllegalArgumentException("previous scope key version must be positive");
        }
        if (previousKeyVersion != null && previousKeyVersion == currentKeyVersion) {
            throw new IllegalArgumentException("current and previous scope key versions must differ");
        }
    }
}

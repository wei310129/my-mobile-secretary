package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import java.util.Objects;

/**
 * 不可逆的 conversation scope 識別。原始 adapter token 僅能存在於當前請求記憶體內。
 */
public record ConversationScopeKey(String digest, int keyVersion) {

    public ConversationScopeKey {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("scope digest must be a SHA-256 hex value");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("scope key version must be positive");
        }
        Objects.requireNonNull(digest, "digest");
    }
}

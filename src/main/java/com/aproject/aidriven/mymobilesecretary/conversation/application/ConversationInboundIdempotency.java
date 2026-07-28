package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Produces a non-reversible persistence key for one inbound conversation delivery. */
public final class ConversationInboundIdempotency {

    private ConversationInboundIdempotency() {
    }

    public static String fromRequestId(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        try {
            byte[] material = ("conversation-request:" + requestId)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

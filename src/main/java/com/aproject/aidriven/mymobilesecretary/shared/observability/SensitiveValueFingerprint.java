package com.aproject.aidriven.mymobilesecretary.shared.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Creates a stable, non-reversible correlation label without retaining a sensitive identifier. */
public final class SensitiveValueFingerprint {

    private static final int DISPLAY_HEX_LENGTH = 16;

    private SensitiveValueFingerprint() {
    }

    public static String of(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
            return digest.substring(0, DISPLAY_HEX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

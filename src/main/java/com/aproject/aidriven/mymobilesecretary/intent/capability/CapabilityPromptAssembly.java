package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned candidate-only prompt and its deterministic SHA-256 content hash. */
public record CapabilityPromptAssembly(
        String promptVersion,
        String promptHash,
        String content) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CapabilityPromptAssembly {
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(promptHash, "promptHash");
        Objects.requireNonNull(content, "content");
        if (promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion must not be blank");
        }
        if (!SHA_256.matcher(promptHash).matches()) {
            throw new IllegalArgumentException("promptHash must be a lowercase SHA-256 value");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}

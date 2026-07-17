package com.aproject.internal.aidispatcher.codex;

import java.time.Instant;

public record CodexCompletion(Status status, String resultCode, Instant completedAt) {

    public CodexCompletion {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        resultCode = bounded(resultCode);
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt is required");
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= 100 ? stripped : stripped.substring(0, 100);
    }

    public enum Status {
        SUCCEEDED,
        FAILED
    }
}

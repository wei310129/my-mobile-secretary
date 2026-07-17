package com.aproject.internal.aidispatcher.codex;

import java.time.Instant;

public record CodexStartReceipt(String externalExecutionId, Instant acceptedAt) {

    public CodexStartReceipt {
        if (externalExecutionId == null || externalExecutionId.isBlank()) {
            throw new IllegalArgumentException("externalExecutionId is required");
        }
        externalExecutionId = externalExecutionId.strip();
        if (acceptedAt == null) {
            throw new IllegalArgumentException("acceptedAt is required");
        }
    }
}

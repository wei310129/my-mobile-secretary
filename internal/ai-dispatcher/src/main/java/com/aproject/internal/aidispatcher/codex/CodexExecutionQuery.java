package com.aproject.internal.aidispatcher.codex;

import java.time.Instant;
import java.util.UUID;

public record CodexExecutionQuery(
        UUID runId,
        long fencingToken,
        String externalExecutionId,
        String externalSessionId,
        Instant requestedAt
) {
    public CodexExecutionQuery {
        if (runId == null || fencingToken <= 0 || requestedAt == null) {
            throw new IllegalArgumentException("runId, positive fencingToken and requestedAt are required");
        }
    }
}

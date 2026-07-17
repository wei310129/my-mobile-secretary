package com.aproject.internal.aidispatcher.codex.cli;

import java.time.Instant;
import java.util.UUID;

record CodexExecutionAuditSnapshot(
        String externalExecutionId,
        UUID runId,
        long fencingToken,
        String status,
        Instant startedAt,
        Instant lastProgressAt,
        Instant finishedAt,
        String terminalEvent,
        Integer exitCode,
        String diagnosticCode
) {
}

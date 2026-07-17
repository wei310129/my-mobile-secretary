package com.aproject.internal.aidispatcher.codex;

import java.util.UUID;

public record CodexRecoveryResult(Outcome outcome, UUID runId, int recoveryAttemptCount) {

    static CodexRecoveryResult of(Outcome outcome, UUID runId, int recoveryAttemptCount) {
        return new CodexRecoveryResult(outcome, runId, recoveryAttemptCount);
    }

    public enum Outcome {
        NO_ACTIVE_RUN,
        HEALTHY,
        LAUNCH_REQUIRED,
        RECOVERED_RUNNING,
        COMPLETED,
        RETRY_PENDING,
        PAUSED,
        STALE
    }
}

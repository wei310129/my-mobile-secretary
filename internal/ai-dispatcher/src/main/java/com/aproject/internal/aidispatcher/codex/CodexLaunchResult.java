package com.aproject.internal.aidispatcher.codex;

import java.util.UUID;

public record CodexLaunchResult(Outcome outcome, UUID runId, String externalExecutionId) {

    static CodexLaunchResult started(UUID runId, String externalExecutionId) {
        return new CodexLaunchResult(Outcome.STARTED, runId, externalExecutionId);
    }

    static CodexLaunchResult of(Outcome outcome, UUID runId) {
        return new CodexLaunchResult(outcome, runId, null);
    }

    public enum Outcome {
        STARTED,
        ALREADY_DISPATCHED,
        NOT_ACTIVE,
        STALE_ACK
    }
}

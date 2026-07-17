package com.aproject.internal.aidispatcher.codex;

public record CodexLifecycleResult(Outcome outcome, long pendingEventCount) {

    static CodexLifecycleResult of(Outcome outcome, long pendingEventCount) {
        return new CodexLifecycleResult(outcome, pendingEventCount);
    }

    public enum Outcome {
        COMPLETED,
        DUPLICATE,
        STALE,
        NOT_FOUND,
        HEARTBEAT_ACCEPTED,
        HEARTBEAT_REJECTED
    }
}

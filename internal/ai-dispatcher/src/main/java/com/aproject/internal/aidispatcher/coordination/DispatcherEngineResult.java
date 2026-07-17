package com.aproject.internal.aidispatcher.coordination;

import java.util.UUID;

public record DispatcherEngineResult(Action action, UUID runId) {

    static DispatcherEngineResult of(Action action, UUID runId) {
        return new DispatcherEngineResult(action, runId);
    }

    public enum Action {
        IDLE,
        WAITING,
        LAUNCHED,
        ACTIVE,
        RECOVERED,
        RECOVERY_PENDING,
        PAUSED
    }
}

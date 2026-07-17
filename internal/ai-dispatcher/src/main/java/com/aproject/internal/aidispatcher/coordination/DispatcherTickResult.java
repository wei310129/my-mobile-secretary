package com.aproject.internal.aidispatcher.coordination;

import com.aproject.internal.aidispatcher.domain.DispatcherState;
import java.time.Instant;
import java.util.UUID;

public record DispatcherTickResult(
        Outcome outcome,
        DispatcherState state,
        UUID runId,
        long eventCount,
        Instant eligibleAt
) {

    static DispatcherTickResult idle() {
        return new DispatcherTickResult(Outcome.IDLE, DispatcherState.IDLE, null, 0, null);
    }

    static DispatcherTickResult waiting(long count, Instant eligibleAt) {
        return new DispatcherTickResult(
                Outcome.WAITING, DispatcherState.WAITING, null, count, eligibleAt);
    }

    static DispatcherTickResult claimed(UUID runId, long count) {
        return new DispatcherTickResult(
                Outcome.CLAIMED, DispatcherState.STARTING, runId, count, null);
    }

    static DispatcherTickResult busy(DispatcherState state) {
        return new DispatcherTickResult(Outcome.BUSY, state, null, 0, null);
    }

    static DispatcherTickResult paused() {
        return new DispatcherTickResult(Outcome.PAUSED, DispatcherState.PAUSED, null, 0, null);
    }

    public enum Outcome {
        IDLE,
        WAITING,
        CLAIMED,
        BUSY,
        PAUSED
    }
}

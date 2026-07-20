package com.aproject.aidriven.mymobilesecretary.schedule.application;

import java.time.Instant;

/** User-relevant schedule lifecycle change, published after domain validation succeeds. */
public record ScheduleLifecycleEvent(
        Long scheduleId, String title, Action action, Instant occurredAt) {

    public enum Action {
        CREATED, REJECTED, CANCELED, COMPLETED
    }
}

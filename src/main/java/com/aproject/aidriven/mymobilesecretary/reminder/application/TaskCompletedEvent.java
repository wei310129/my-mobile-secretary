package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Instant;

/** Published only after a non-recurring task reaches its terminal confirmed state. */
public record TaskCompletedEvent(Long taskId, String title, Instant completedAt) {

    /** Compatibility constructor for listeners/tests that only need identity and time. */
    public TaskCompletedEvent(Long taskId, Instant completedAt) {
        this(taskId, null, completedAt);
    }
}

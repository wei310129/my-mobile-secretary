package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import java.time.Instant;
import java.util.UUID;

public record CalendarReminderLifecycleEvent(
        UUID ruleId, Action action, String nodeLabel, Instant occurredAt) {

    public enum Action {
        CREATED,
        CANCELED
    }
}

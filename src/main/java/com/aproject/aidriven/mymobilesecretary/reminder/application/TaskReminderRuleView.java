package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Instant;

public record TaskReminderRuleView(
        long taskId, Instant remindAt, Status status, long revision) {

    public enum Status {
        ACTIVE,
        TRIGGERED,
        CANCELED
    }
}

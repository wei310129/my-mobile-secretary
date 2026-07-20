package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Instant;

/** A reminder passed all Java safety gates and was actually triggered. */
public record ReminderTriggeredEvent(
        Long reminderId, Long taskId, String taskTitle, String reason, Instant triggeredAt) {
}

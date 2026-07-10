package com.aproject.aidriven.mymobilesecretary.api.reminder;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderStatus;
import java.time.Instant;

/** 提醒的 API 回應格式。 */
public record ReminderResponse(
        Long id,
        Long taskId,
        ReminderStatus status,
        Instant triggeredAt,
        Instant confirmedAt,
        String triggerReason,
        Instant createdAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static ReminderResponse from(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getTaskId(),
                reminder.getStatus(),
                reminder.getTriggeredAt(),
                reminder.getConfirmedAt(),
                reminder.getTriggerReason(),
                reminder.getCreatedAt());
    }
}

package com.aproject.aidriven.mymobilesecretary.api.task;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 建立任務的請求。
 *
 * @param title       任務標題(必填)
 * @param description 詳細說明(選填)
 * @param priority    優先度,未給時預設 NORMAL
 * @param dueAt       期限(選填)
 */
public record CreateTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        TaskPriority priority,
        Instant dueAt
) {

    /** priority 未指定時的預設值。 */
    public TaskPriority priorityOrDefault() {
        return priority == null ? TaskPriority.NORMAL : priority;
    }
}

package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Arguments for creating a deadline-based task rather than a time-blocked schedule. */
public record CreateTaskPayload(
        @NotBlank @Size(max = 200) String title,
        OffsetDateTime dueAt,
        @Size(max = 200) String placeName,
        TaskPriority priority,
        Task.Category category,
        Task.Recurrence recurrence,
        Task.ConditionType condition) {
}

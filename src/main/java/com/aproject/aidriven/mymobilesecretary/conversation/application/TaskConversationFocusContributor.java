package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates a task resource through its domain service under the current workspace context. */
@Component
public final class TaskConversationFocusContributor implements ConversationFocusContributor {

    private final TaskService tasks;

    public TaskConversationFocusContributor(TaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public String rootDomain() {
        return "TASK";
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        Long taskId = resourceId(target, "task:");
        if (taskId == null) {
            return false;
        }
        try {
            Task task = tasks.getTask(taskId);
            return task.getTitle().equals(target.safeLabel());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static Long resourceId(ConversationFocusTargetResolver.ResourceTarget target, String prefix) {
        if (target == null || !prefix.equals(target.routingKey() == null ? null
                : target.routingKey().substring(0, Math.min(prefix.length(), target.routingKey().length())))) {
            return null;
        }
        try {
            return Long.valueOf(target.routingKey().substring(prefix.length()));
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}

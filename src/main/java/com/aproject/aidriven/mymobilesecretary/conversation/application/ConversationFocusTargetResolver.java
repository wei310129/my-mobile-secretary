package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolves focus roots only from a handler's persisted, typed outcome. */
@Component
public final class ConversationFocusTargetResolver {

    public Optional<ResourceTarget> resolve(IntentCommand command, IntentResult result) {
        if (command == null || result == null) {
            return Optional.empty();
        }
        return resolve(result);
    }

    public Optional<ResourceTarget> resolve(IntentResult result) {
        if (result == null) {
            return Optional.empty();
        }
        if (result.focusBinding() != null) {
            ConversationFocusBinding binding = result.focusBinding();
            return Optional.of(new ResourceTarget(
                    binding.domain(), binding.routingKey(), binding.workflowId(),
                    binding.safeLabel(), binding.activityCode(), binding.activityLabel()));
        }
        if (result.task() != null) {
            return task(result.task());
        }
        if (result.decision() != null) {
            return schedule(result.decision());
        }
        return Optional.empty();
    }

    private static Optional<ResourceTarget> task(Task task) {
        if (task.getId() == null || blank(task.getTitle())) {
            return Optional.empty();
        }
        return Optional.of(new ResourceTarget("TASK", "task:" + task.getId(), task.getTitle()));
    }

    private static Optional<ResourceTarget> schedule(ScheduleDecision decision) {
        ScheduleItem item = decision.item();
        if (item == null || item.getId() == null || blank(item.getTitle())) {
            return Optional.empty();
        }
        return Optional.of(new ResourceTarget(
                "SCHEDULE", "schedule:" + item.getId(), item.getTitle()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ResourceTarget(
            String domain,
            String routingKey,
            UUID workflowId,
            String safeLabel,
            String activityCode,
            String activityLabel) {

        public ResourceTarget(String domain, String routingKey, String safeLabel) {
            this(domain, routingKey, null, safeLabel, null, null);
        }

        public boolean workflow() {
            return workflowId != null;
        }
    }
}

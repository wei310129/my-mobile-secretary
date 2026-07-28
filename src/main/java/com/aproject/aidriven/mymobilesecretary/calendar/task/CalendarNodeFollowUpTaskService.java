package com.aproject.aidriven.mymobilesecretary.calendar.task;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarNodeFollowUpTaskService {

    private static final Duration MAX_DELAY = Duration.ofDays(30);

    private final CalendarApplicationService calendars;
    private final CalendarTaskBindingService bindings;
    private final TaskService tasks;
    private final CalendarTimeNodeRepository nodes;

    public CalendarNodeFollowUpTaskService(
            CalendarApplicationService calendars,
            CalendarTaskBindingService bindings,
            TaskService tasks,
            CalendarTimeNodeRepository nodes) {
        this.calendars = calendars;
        this.bindings = bindings;
        this.tasks = tasks;
        this.nodes = nodes;
    }

    public Task createAfterNode(
            String requestKey,
            String planKey,
            String nodeKey,
            String title,
            Duration delay) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Follow-up task title is required");
        }
        Duration effectiveDelay = delay == null ? Duration.ZERO : delay;
        if (effectiveDelay.isNegative() || effectiveDelay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "Follow-up task delay must be between zero and 30 days");
        }
        var node = calendars.lockNodeForReminder(planKey, nodeKey);
        return createForNode(requestKey, title, effectiveDelay, node);
    }

    public Task createAfterNode(
            String requestKey,
            UUID planId,
            UUID nodeId,
            String title,
            Duration delay) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Follow-up task title is required");
        }
        Duration effectiveDelay = delay == null ? Duration.ZERO : delay;
        if (effectiveDelay.isNegative() || effectiveDelay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "Follow-up task delay must be between zero and 30 days");
        }
        var context = WorkspaceContextHolder.requireContext();
        CalendarTimeNodeEntity node = nodes.findByIdAndWorkspaceIdAndCreatedByUserId(
                        nodeId, context.workspaceId(), context.actorId())
                .filter(found -> found.getPlanId().equals(planId))
                .orElseThrow(() -> new NotFoundException(
                        "Calendar node", "requested node"));
        return createForNode(requestKey, title, effectiveDelay, node);
    }

    private Task createForNode(
            String requestKey,
            String title,
            Duration delay,
            CalendarTimeNodeEntity node) {
        CalendarTaskBindingView replay = bindings.findByRequestKey(requestKey);
        if (replay != null) {
            return tasks.getTask(replay.taskId());
        }
        Instant deadline = node.getResolvedTime().plus(delay);
        Task task = tasks.createTask(
                title.strip(), null, TaskPriority.NORMAL, deadline);
        bindings.bind(
                requestKey,
                task.getId(),
                CalendarTaskTarget.node(node.getPlanId(), node.getId()));
        return task;
    }
}

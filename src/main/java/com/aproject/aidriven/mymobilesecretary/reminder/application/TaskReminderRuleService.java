package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
public class TaskReminderRuleService {

    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final ReminderScheduleService schedule;
    private final Clock clock;

    public TaskReminderRuleService(
            JdbcTemplate jdbc,
            TaskRepository tasks,
            ReminderScheduleService schedule,
            Clock clock) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.schedule = schedule;
        this.clock = clock;
    }

    public TaskReminderRuleView create(String requestKey, long taskId, Instant remindAt) {
        WorkspaceContext context = tenantContext();
        if (remindAt == null || !remindAt.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("Task reminder must be in the future");
        }
        tasks.findByIdAndCreatedByUserId(taskId, context.actorId())
                .orElseThrow(() -> new NotFoundException("Task", "requested task"));
        String requestHash = hash(requireRequestKey(requestKey));
        String payloadHash = hash(taskId + "|" + remindAt);
        TaskReminderRuleView replay = findByRequest(requestHash, context, payloadHash);
        if (replay != null) {
            return replay;
        }
        List<TaskReminderRuleView> active = activeForTask(taskId, context);
        if (!active.isEmpty()) {
            TaskReminderRuleView existing = active.getFirst();
            if (existing.remindAt().equals(remindAt)) {
                return existing;
            }
            throw new BusinessException(
                    "TASK_REMINDER_RULE_EXISTS", "Task already has an active reminder rule");
        }
        Instant now = Instant.now(clock);
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO task_reminder_rule (
                            id, task_id, remind_at, status, revision,
                            creation_request_hash, creation_payload_hash,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (workspace_id, created_by_user_id, creation_request_hash)
                        DO NOTHING
                        """,
                        UUID.randomUUID(),
                        taskId,
                        Timestamp.from(remindAt),
                        requestHash,
                        payloadHash,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        context.workspaceId(),
                        context.actorId());
        TaskReminderRuleView result = findByRequest(requestHash, context, payloadHash);
        if (result == null) {
            throw new IllegalStateException("Task reminder arbitration produced no row");
        }
        if (inserted == 1) {
            afterCommit(() -> schedule.scheduleDueReminder(taskId, remindAt));
        }
        return result;
    }

    public TaskReminderRuleView reschedule(long taskId, Instant remindAt, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        if (remindAt == null || !remindAt.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("Task reminder must be in the future");
        }
        int changed =
                jdbc.update(
                        """
                        UPDATE task_reminder_rule
                           SET remind_at = ?, revision = revision + 1, updated_at = ?
                         WHERE task_id = ? AND status = 'ACTIVE' AND revision = ?
                           AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Timestamp.from(remindAt),
                        Timestamp.from(Instant.now(clock)),
                        taskId,
                        expectedRevision,
                        context.workspaceId(),
                        context.actorId());
        if (changed != 1) {
            throw new BusinessException(
                    "TASK_REMINDER_REVISION_CONFLICT",
                    "Task reminder rule changed or is no longer active");
        }
        afterCommit(() -> schedule.scheduleDueReminder(taskId, remindAt));
        return activeForTask(taskId, context).getFirst();
    }

    public TaskReminderRuleView cancel(long taskId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        int changed =
                jdbc.update(
                        """
                        UPDATE task_reminder_rule
                           SET status = 'CANCELED', revision = revision + 1, updated_at = ?
                         WHERE task_id = ? AND status = 'ACTIVE' AND revision = ?
                           AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Timestamp.from(Instant.now(clock)),
                        taskId,
                        expectedRevision,
                        context.workspaceId(),
                        context.actorId());
        if (changed != 1) {
            throw new BusinessException(
                    "TASK_REMINDER_REVISION_CONFLICT",
                    "Task reminder rule changed or is no longer active");
        }
        afterCommit(() -> schedule.removeDueReminder(taskId));
        return findLatest(taskId, context);
    }

    @Transactional(readOnly = true)
    public TaskReminderRuleView getActive(long taskId) {
        WorkspaceContext context = tenantContext();
        return activeForTask(taskId, context).stream()
                .findFirst()
                .orElseThrow(
                        () -> new NotFoundException("Task reminder rule", "requested task"));
    }

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        closeActive(event.taskId(), "CANCELED", event.completedAt());
    }

    @EventListener
    public void onTaskCanceled(TaskCanceledEvent event) {
        closeActive(event.taskId(), "CANCELED", event.canceledAt());
    }

    @EventListener
    public void onReminderTriggered(ReminderTriggeredEvent event) {
        closeActive(event.taskId(), "TRIGGERED", event.triggeredAt());
    }

    private void closeActive(long taskId, String status, Instant now) {
        WorkspaceContext context = tenantContext();
        jdbc.update(
                """
                UPDATE task_reminder_rule
                   SET status = ?, revision = revision + 1, updated_at = ?
                 WHERE task_id = ? AND status = 'ACTIVE'
                   AND workspace_id = ? AND created_by_user_id = ?
                """,
                status,
                Timestamp.from(now),
                taskId,
                context.workspaceId(),
                context.actorId());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private TaskReminderRuleView findByRequest(
            String requestHash, WorkspaceContext context, String expectedPayloadHash) {
        List<StoredRule> found =
                jdbc.query(
                        """
                        SELECT task_id, remind_at, status, revision, creation_payload_hash
                          FROM task_reminder_rule
                         WHERE creation_request_hash = ?
                           AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        (row, ignored) ->
                                new StoredRule(
                                        row.getLong("task_id"),
                                        row.getTimestamp("remind_at").toInstant(),
                                        TaskReminderRuleView.Status.valueOf(
                                                row.getString("status")),
                                        row.getLong("revision"),
                                        row.getString("creation_payload_hash")),
                        requestHash,
                        context.workspaceId(),
                        context.actorId());
        if (found.isEmpty()) {
            return null;
        }
        StoredRule stored = found.getFirst();
        if (!stored.payloadHash().equals(expectedPayloadHash)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for a different task reminder");
        }
        return stored.view();
    }

    private List<TaskReminderRuleView> activeForTask(long taskId, WorkspaceContext context) {
        return jdbc.query(
                """
                SELECT task_id, remind_at, status, revision
                  FROM task_reminder_rule
                 WHERE task_id = ? AND status = 'ACTIVE'
                   AND workspace_id = ? AND created_by_user_id = ?
                """,
                (row, ignored) -> map(row),
                taskId,
                context.workspaceId(),
                context.actorId());
    }

    private TaskReminderRuleView findLatest(long taskId, WorkspaceContext context) {
        return jdbc.query(
                        """
                        SELECT task_id, remind_at, status, revision
                          FROM task_reminder_rule
                         WHERE task_id = ?
                           AND workspace_id = ? AND created_by_user_id = ?
                         ORDER BY updated_at DESC LIMIT 1
                        """,
                        (row, ignored) -> map(row),
                        taskId,
                        context.workspaceId(),
                        context.actorId())
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new NotFoundException("Task reminder rule", "requested task"));
    }

    private static TaskReminderRuleView map(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new TaskReminderRuleView(
                row.getLong("task_id"),
                row.getTimestamp("remind_at").toInstant(),
                TaskReminderRuleView.Status.valueOf(row.getString("status")),
                row.getLong("revision"));
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Task reminder rule requires tenant scope");
        }
        return context;
    }

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
        return value.strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record StoredRule(
            long taskId,
            Instant remindAt,
            TaskReminderRuleView.Status status,
            long revision,
            String payloadHash) {

        private TaskReminderRuleView view() {
            return new TaskReminderRuleView(taskId, remindAt, status, revision);
        }
    }
}

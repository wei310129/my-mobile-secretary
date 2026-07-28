package com.aproject.aidriven.mymobilesecretary.calendar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarTaskBindingServiceTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T06:00:00Z");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired private CalendarTaskBindingService bindings;
    @Autowired private CalendarNodeFollowUpTaskService followUps;
    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private TaskService tasks;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void nodeBindingIsIdempotentExactlyOnceAndTaskCompletionKeepsCalendarHistory() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "連結者");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("航班", "check-in"));

        CalendarTaskBindingView created = inContext(context, () -> bindings.bind(
                "bind-node-once",
                fixture.task().getId(),
                CalendarTaskTarget.node(fixture.planId(), fixture.nodeId())));
        CalendarTaskBindingView replay = inContext(context, () -> bindings.bind(
                "bind-node-once",
                fixture.task().getId(),
                CalendarTaskTarget.node(fixture.planId(), fixture.nodeId())));
        inContext(context, () -> tasks.confirmTask(fixture.task().getId()));

        assertThat(created).isEqualTo(replay);
        assertThat(created.targetKind()).isEqualTo(CalendarTaskTarget.TargetKind.NODE);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("calendar_time_node")).isEqualTo(1L);
    }

    @Test
    void oneTaskCannotSilentlyMoveToAnotherCalendarTarget() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "不可偷移");
        WorkspaceContext context = context(actor, workspace);
        Fixture first = inContext(context, () -> fixture("第一段", "first"));
        Fixture second = inContext(context, () -> fixture("第二段", "second"));
        inContext(context, () -> bindings.bind(
                "bind-first",
                first.task().getId(),
                CalendarTaskTarget.plan(first.planId())));

        assertThatThrownBy(() -> inContext(context, () -> bindings.bind(
                        "bind-second",
                        first.task().getId(),
                        CalendarTaskTarget.plan(second.planId()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already bound");
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
    }

    @Test
    void actorBoundaryFailsClosedWithoutLeakingBindingExistence() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "擁有者");
        seedUser(peer, "同 workspace 其他 actor");
        Fixture fixture = inContext(context(owner, workspace), () -> fixture("私有行程", "private"));
        inContext(context(owner, workspace), () -> bindings.bind(
                "private-binding",
                fixture.task().getId(),
                CalendarTaskTarget.plan(fixture.planId())));

        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> bindings.getForTask(fixture.task().getId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void databaseRejectsCrossActorTaskAndCalendarComposition() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "行程擁有者");
        seedUser(peer, "任務擁有者");
        Fixture calendar = inContext(context(owner, workspace), () -> fixture("擁有者行程", "owner"));
        Task peerTask = inContext(context(peer, workspace), () ->
                tasks.createTask("其他人的待辦", null, TaskPriority.NORMAL, null));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO calendar_task_binding (
                            id, task_id, target_kind, plan_id,
                            creation_request_hash, creation_payload_hash, created_at,
                            workspace_id, created_by_user_id)
                        VALUES (?, ?, 'PLAN', ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        peerTask.getId(),
                        calendar.planId(),
                        "a".repeat(64),
                        "b".repeat(64),
                        NOW,
                        workspace,
                        owner))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nodeAfterTrackingCreatesLinkedTaskInsteadOfPositiveOffsetCalendarReminder() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "節點後追蹤");
        WorkspaceContext context = context(actor, workspace);
        Instant nodeTime = NOW.plusSeconds(7200);
        inContext(context, () -> calendars.createPlan(new CreateCalendarPlanCommand(
                "follow-up-plan",
                "抵達機場",
                CalendarPlacement.point(nodeTime, TAIPEI),
                null,
                null,
                null,
                java.util.List.of(),
                java.util.List.of(CalendarNodeDraft.of(
                        CalendarTimeNode.absolute("arrival", "抵達", nodeTime))))));

        Task created = inContext(context, () -> followUps.createAfterNode(
                "follow-up-task-once",
                "follow-up-plan",
                "arrival",
                "抵達後回報平安",
                Duration.ofHours(1)));
        Task replay = inContext(context, () -> followUps.createAfterNode(
                "follow-up-task-once",
                "follow-up-plan",
                "arrival",
                "抵達後回報平安",
                Duration.ofHours(1)));

        assertThat(replay.getId()).isEqualTo(created.getId());
        assertThat(created.getDueAt()).isEqualTo(nodeTime.plus(Duration.ofHours(1)));
        assertThat(count("task")).isEqualTo(1L);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
        assertThat(count("calendar_reminder_rule")).isZero();
        assertThat(count("task_reminder_rule")).isZero();

        inContext(context, () -> tasks.cancelTask(created.getId()));
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("calendar_time_node")).isEqualTo(1L);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
    }

    private Fixture fixture(String title, String nodeKey) {
        Task task = tasks.createTask(title + "後續", null, TaskPriority.NORMAL, null);
        UUID planId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.interval(
                        NOW.plusSeconds(3600), NOW.plusSeconds(7200), TAIPEI),
                NOW));
        nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                nodeId,
                planId,
                null,
                CalendarTimeNode.absolute(nodeKey, nodeKey, NOW.plusSeconds(3600)),
                NOW));
        return new Fixture(task, planId, nodeId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(Task task, UUID planId, UUID nodeId) {}
}

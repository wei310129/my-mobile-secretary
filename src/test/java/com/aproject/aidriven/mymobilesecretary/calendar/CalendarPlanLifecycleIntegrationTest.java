package com.aproject.aidriven.mymobilesecretary.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarAdoptionService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderOwnerKind;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskTarget;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarPlanLifecycleIntegrationTest extends IntegrationTestBase {

    private static final Instant START = Instant.parse("2026-08-03T01:00:00Z");

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarPlanLifecycleService lifecycle;
    @Autowired private CalendarAdoptionService adoptions;
    @Autowired private CalendarReminderApplicationService reminders;
    @Autowired private CalendarKnowledgeBindingService knowledgeBindings;
    @Autowired private CalendarTaskBindingService taskBindings;
    @Autowired private UserKnowledgeService knowledge;
    @Autowired private TaskService tasks;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void cancelAndArchiveCloseCalendarDependentsButPreserveIndependentKnowledge() {
        WorkspaceContext owner = seedContext("lifecycle owner");
        Fixture fixture = inContext(owner, () -> fixture("lifecycle-plan", "郵輪行程"));
        long baselineRecords = count("tagged_life_record", owner);

        CalendarPlanLifecycleView canceled =
                inContext(owner, () -> lifecycle.cancel(fixture.planId(), 1));

        assertThat(canceled.status()).isEqualTo(CalendarPlanStatus.CANCELED);
        assertThat(canceled.revision()).isEqualTo(2);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(status("calendar_plan", owner)).isEqualTo("CANCELED");
        assertThat(status("calendar_adoption", owner)).isEqualTo("CANCELED");
        assertThat(status("calendar_reminder_rule", owner)).isEqualTo("CANCELED");
        assertThat(status("calendar_reminder_occurrence", owner)).isEqualTo("CANCELED");
        assertThat(status("calendar_knowledge_fact_binding", owner))
                .isEqualTo("ARCHIVED");
        assertThat(count("user_knowledge_fact", owner)).isEqualTo(1);
        assertThat(inContext(owner, adoptions::constraints)).isEmpty();
        assertThat(count("tagged_life_record", owner)).isEqualTo(baselineRecords + 1);

        CalendarPlanLifecycleView archived =
                inContext(owner, () -> lifecycle.archive(fixture.planId(), 2));

        assertThat(archived.status()).isEqualTo(CalendarPlanStatus.ARCHIVED);
        assertThat(archived.revision()).isEqualTo(3);
        assertThat(archived.canceledAt()).isEqualTo(canceled.canceledAt());
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(status("calendar_plan", owner)).isEqualTo("ARCHIVED");
        assertThat(count("user_knowledge_fact", owner)).isEqualTo(1);
        assertThat(count("tagged_life_record", owner)).isEqualTo(baselineRecords + 2);
    }

    @Test
    void staleOrCrossActorLifecycleRequestLeavesPlanAndDependentsUnchanged() {
        WorkspaceContext owner = seedContext("lifecycle guarded owner");
        WorkspaceContext outsider = seedContext("lifecycle outsider");
        Fixture fixture = inContext(owner, () -> fixture("guarded-plan", "受保護行程"));
        long baselineRecords = count("tagged_life_record", owner);

        assertThatThrownBy(
                        () -> inContext(owner, () -> lifecycle.cancel(fixture.planId(), 2)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> inContext(outsider, () -> lifecycle.archive(fixture.planId(), 1)))
                .isInstanceOf(NotFoundException.class);

        assertThat(status("calendar_plan", owner)).isEqualTo("ACTIVE");
        assertThat(status("calendar_adoption", owner)).isEqualTo("ACTIVE");
        assertThat(status("calendar_reminder_rule", owner)).isEqualTo("ACTIVE");
        assertThat(status("calendar_reminder_occurrence", owner)).isEqualTo("PENDING");
        assertThat(status("calendar_knowledge_fact_binding", owner)).isEqualTo("ACTIVE");
        assertThat(count("tagged_life_record", owner)).isEqualTo(baselineRecords);
    }

    @Test
    void activePlanCanBeArchivedWithoutInventingCancellationTimestamp() {
        WorkspaceContext owner = seedContext("direct archive owner");
        Fixture fixture = inContext(owner, () -> fixture("direct-archive", "封存行程"));

        CalendarPlanLifecycleView archived =
                inContext(owner, () -> lifecycle.archive(fixture.planId(), 1));

        assertThat(archived.status()).isEqualTo(CalendarPlanStatus.ARCHIVED);
        assertThat(archived.canceledAt()).isNull();
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_plan
                        WHERE id = ? AND canceled_at IS NULL
                          AND archived_at IS NOT NULL
                        """,
                        Long.class,
                        fixture.planId()))
                .isEqualTo(1);
    }

    @Test
    void pureTaskBindingDoesNotCreateLifeRecord() {
        WorkspaceContext owner = seedContext("pure binding owner");
        Fixture fixture = inContext(owner, () -> fixture("pure-binding", "單純連結"));
        Task task = inContext(owner, () -> tasks.createTask(
                "準備資料", null, TaskPriority.NORMAL, null));
        long beforeBinding = count("tagged_life_record", owner);

        inContext(owner, () -> taskBindings.bind(
                "pure-binding-once",
                task.getId(),
                CalendarTaskTarget.plan(fixture.planId())));

        assertThat(count("calendar_task_binding", owner)).isEqualTo(1);
        assertThat(count("tagged_life_record", owner)).isEqualTo(beforeBinding);
    }

    private Fixture fixture(String key, String title) {
        calendars.createPlan(new CreateCalendarPlanCommand(
                key,
                title,
                CalendarPlacement.point(START, ZoneId.of("Asia/Taipei")),
                "旅行",
                null,
                null,
                List.of(),
                List.of(CalendarNodeDraft.of(
                        CalendarTimeNode.absolute("anchor", "出發", START)))));
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        UUID planId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_plan
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND title = ?
                """,
                UUID.class,
                context.workspaceId(),
                context.actorId(),
                title);
        adoptions.adopt(key, List.of("anchor"));
        reminders.createRelative(
                key,
                "anchor",
                Duration.ofMinutes(-30),
                CalendarReminderOwnerKind.PERSONAL,
                CalendarReminderDeliveryMode.ONCE,
                null,
                null,
                NotificationChannel.LOG);
        UserKnowledgeFact fact = knowledge.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                title,
                "提早三十分鐘集合");
        knowledgeBindings.bindFact(
                "lifecycle-binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        return new Fixture(planId);
    }

    private WorkspaceContext seedContext(String label) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                label,
                actor);
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private String status(String table, WorkspaceContext context) {
        return jdbc.queryForObject(
                "SELECT status FROM " + table
                        + " WHERE workspace_id = ? AND created_by_user_id = ?",
                String.class,
                context.workspaceId(),
                context.actorId());
    }

    private long count(String table, WorkspaceContext context) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table
                        + " WHERE workspace_id = ? AND created_by_user_id = ?",
                Long.class,
                context.workspaceId(),
                context.actorId());
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(UUID planId) {
    }
}

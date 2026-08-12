package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarReminderApplicationServiceTest extends IntegrationTestBase {

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarReminderApplicationService reminders;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void enforcesIndependentPersonalAndTemplateQuotaAndRejectsPositiveOffsetAtomically() {
        TestScope scope = seedCalendar("calendar-reminder-quota", false);
        var personalRuleIds = new ArrayList<UUID>();

        inContext(scope.context(), () -> {
            for (int index = 0; index < 8; index++) {
                personalRuleIds.add(
                        reminders.createRelative(
                                        scope.planKey(),
                                        "anchor",
                                        Duration.ofMinutes(-index),
                                        CalendarReminderOwnerKind.PERSONAL,
                                        CalendarReminderDeliveryMode.ONCE,
                                        null,
                                        null,
                                        NotificationChannel.LOG)
                                .ruleId());
            }
            for (int index = 0; index < 8; index++) {
                reminders.createRelative(
                        scope.planKey(),
                        "anchor",
                        Duration.ofMinutes(-index),
                        CalendarReminderOwnerKind.SHARED_TEMPLATE,
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        NotificationChannel.LOG);
            }
        });

        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createRelative(
                                scope.planKey(),
                                "anchor",
                                Duration.ofMinutes(-9),
                                CalendarReminderOwnerKind.PERSONAL,
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("8");
        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createRelative(
                                scope.planKey(),
                                "anchor",
                                Duration.ofMinutes(-1),
                                CalendarReminderOwnerKind.PERSONAL,
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same");
        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createRelative(
                                scope.planKey(),
                                "anchor",
                                Duration.ofMinutes(1),
                                CalendarReminderOwnerKind.PERSONAL,
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Task");
        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createAbsolute(
                                scope.planKey(),
                                "anchor",
                                scope.nodeTime().plusSeconds(1),
                                CalendarReminderOwnerKind.PERSONAL,
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Task");
        inContext(scope.context(), () -> reminders.cancel(personalRuleIds.getFirst()));

        assertThat(count("calendar_reminder_rule", scope.workspaceId())).isEqualTo(16);
        assertThat(count("calendar_reminder_occurrence", scope.workspaceId())).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM tagged_life_record
                        WHERE workspace_id = ? AND record_type = 'REMINDER'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(17L);
    }

    @Test
    void exactIdEntryLocksOwnedNodeChecksRevisionAndCreatesPersonalRules() {
        TestScope scope = seedCalendar("calendar-reminder-exact-id", false);
        UUID planId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_plan
                WHERE workspace_id = ? AND created_by_user_id = ?
                """,
                UUID.class,
                scope.workspaceId(),
                scope.context().actorId());
        UUID nodeId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_time_node
                WHERE plan_id = ? AND node_key = 'anchor'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                UUID.class,
                planId,
                scope.workspaceId(),
                scope.context().actorId());

        CalendarReminderRuleView relative = inContext(
                scope.context(),
                () -> reminders.createPersonalRelativeForNode(
                        planId,
                        nodeId,
                        1,
                        Duration.ofMinutes(-15),
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        NotificationChannel.LOG));
        CalendarReminderRuleView absolute = inContext(
                scope.context(),
                () -> reminders.createPersonalAbsoluteForNode(
                        planId,
                        nodeId,
                        1,
                        scope.nodeTime().minus(Duration.ofMinutes(30)),
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        NotificationChannel.LOG));

        assertThat(relative.ownerKind()).isEqualTo(CalendarReminderOwnerKind.PERSONAL);
        assertThat(relative.firstScheduledAt())
                .isEqualTo(scope.nodeTime()
                        .minus(Duration.ofMinutes(15))
                        .truncatedTo(ChronoUnit.MICROS));
        assertThat(absolute.ownerKind()).isEqualTo(CalendarReminderOwnerKind.PERSONAL);
        assertThat(count("calendar_reminder_rule", scope.workspaceId())).isEqualTo(2L);
        assertThat(count("calendar_reminder_occurrence", scope.workspaceId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_rule
                        WHERE workspace_id = ? AND owner_kind = 'SHARED_TEMPLATE'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isZero();

        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createPersonalRelativeForNode(
                                planId,
                                nodeId,
                                2,
                                Duration.ofMinutes(-5),
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed");

        UUID peer = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, 'peer', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                peer);
        WorkspaceContext peerContext =
                new WorkspaceContext(peer, scope.workspaceId(), WorkspaceChannel.TEST);
        assertThatThrownBy(() -> inContext(
                        peerContext,
                        () -> reminders.createPersonalRelativeForNode(
                                planId,
                                nodeId,
                                1,
                                Duration.ofMinutes(-5),
                                CalendarReminderDeliveryMode.ONCE,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_reminder_rule", scope.workspaceId())).isEqualTo(2L);
    }

    @Test
    void nodeRevisionRematerializesRelativeRuleAndMovesAbsoluteRuleToReview() {
        TestScope scope = seedCalendar("calendar-reminder-revision", false);
        Instant original = scope.nodeTime();
        long baselineScheduleRecords = jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE workspace_id = ? AND record_type = 'SCHEDULE'
                """,
                Long.class,
                scope.workspaceId());
        inContext(scope.context(), () -> {
            reminders.createRelative(
                    scope.planKey(),
                    "anchor",
                    Duration.ofMinutes(-15),
                    CalendarReminderOwnerKind.PERSONAL,
                    CalendarReminderDeliveryMode.ONCE,
                    null,
                    null,
                    NotificationChannel.LOG);
            reminders.createAbsolute(
                    scope.planKey(),
                    "anchor",
                    original.minus(Duration.ofMinutes(30)),
                    CalendarReminderOwnerKind.PERSONAL,
                    CalendarReminderDeliveryMode.ONCE,
                    null,
                    null,
                    NotificationChannel.LOG);
            calendars.reviseLockedNode(
                    scope.planKey(), "anchor", original.plus(Duration.ofHours(1)), 1);
        });

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_occurrence
                        WHERE workspace_id = ? AND status = 'CANCELED'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(2L);
        Instant rematerialized = jdbc.queryForObject(
                """
                SELECT scheduled_at FROM calendar_reminder_occurrence
                WHERE workspace_id = ? AND status = 'PENDING'
                """,
                Instant.class,
                scope.workspaceId());
        assertThat(rematerialized)
                .isCloseTo(
                        original.plus(Duration.ofMinutes(45)),
                        within(1, ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM tagged_life_record
                        WHERE workspace_id = ? AND record_type = 'SCHEDULE'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(baselineScheduleRecords + 1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_rule
                        WHERE workspace_id = ? AND status = 'REVIEW_REQUIRED'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(1L);
    }

    @Test
    void adaptiveDepartureRemindersAreAtomicAndMoveWithDepartureNodeRevision() {
        TestScope scope = seedCalendar("calendar-adaptive-departure-reminder", false);
        Instant original = scope.nodeTime();

        List<CalendarReminderRuleView> created = inContext(
                scope.context(),
                () -> reminders.createAdaptiveDepartureReminders(
                        scope.planKey(),
                        "anchor",
                        Duration.ofMinutes(31),
                        CalendarReminderDeliveryMode.ONCE,
                        NotificationChannel.LOG));

        assertThat(created).extracting(CalendarReminderRuleView::firstScheduledAt)
                .containsExactly(original.minus(Duration.ofMinutes(30)), original);
        inContext(scope.context(), () -> calendars.reviseLockedNode(
                scope.planKey(), "anchor", original.plus(Duration.ofHours(1)), 1));
        assertThat(jdbc.queryForList(
                        """
                        SELECT scheduled_at FROM calendar_reminder_occurrence
                        WHERE workspace_id = ? AND status = 'PENDING'
                        ORDER BY scheduled_at
                        """,
                        Instant.class,
                        scope.workspaceId()))
                .containsExactly(
                        original.plus(Duration.ofMinutes(30)),
                        original.plus(Duration.ofHours(1)));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_rule
                        WHERE workspace_id = ? AND status = 'ACTIVE'
                        """,
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(2L);
    }

    @Test
    void reviewedFixedStartReminderIsCanceledExactlyOnceWithinItsPlanNodeScope() {
        TestScope scope = seedCalendar("calendar-reviewed-start-reminder", false);
        Instant original = scope.nodeTime();
        UUID planId = jdbc.queryForObject(
                "SELECT id FROM calendar_plan WHERE workspace_id = ?",
                UUID.class,
                scope.workspaceId());

        inContext(scope.context(), () -> {
            reminders.createAbsolute(
                    scope.planKey(),
                    "anchor",
                    original.minus(Duration.ofMinutes(20)),
                    CalendarReminderOwnerKind.PERSONAL,
                    CalendarReminderDeliveryMode.ONCE,
                    null,
                    null,
                    NotificationChannel.LOG);
            calendars.reviseLockedNode(
                    scope.planKey(), "anchor", original.plus(Duration.ofHours(1)), 1);
        });

        assertThat(inContext(
                        scope.context(),
                        () -> reminders.cancelPersonalStartRemindersRequiringReview(
                                planId, "anchor")))
                .isEqualTo(1);
        assertThat(inContext(
                        scope.context(),
                        () -> reminders.cancelPersonalStartRemindersRequiringReview(
                                planId, "anchor")))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_reminder_rule WHERE workspace_id = ? AND status = 'REVIEW_REQUIRED'",
                        Long.class,
                        scope.workspaceId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_reminder_rule WHERE workspace_id = ? AND status = 'CANCELED'",
                        Long.class,
                        scope.workspaceId()))
                .isEqualTo(1L);
    }

    @Test
    void criticalAckChoiceIsExplicitAndAcknowledgementCancelsFutureEscalation() {
        TestScope scope = seedCalendar("calendar-reminder-ack", true);

        assertThatThrownBy(() -> inContext(
                        scope.context(),
                        () -> reminders.createRelative(
                                scope.planKey(),
                                "anchor",
                                Duration.ZERO,
                                CalendarReminderOwnerKind.PERSONAL,
                                null,
                                null,
                                null,
                                NotificationChannel.LOG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACK");

        var rule = inContext(
                scope.context(),
                () -> reminders.createRelative(
                        scope.planKey(),
                        "anchor",
                        Duration.ZERO,
                        CalendarReminderOwnerKind.PERSONAL,
                        CalendarReminderDeliveryMode.ACK_REQUIRED,
                        Duration.ofMinutes(5),
                        3,
                        NotificationChannel.LOG));
        jdbc.update(
                """
                UPDATE calendar_reminder_occurrence
                SET status = 'ENQUEUED'
                WHERE rule_id = ?
                """,
                rule.ruleId());
        inContext(scope.context(), () -> reminders.materializeNextAck(rule.ruleId(), 0));
        inContext(scope.context(), () -> reminders.acknowledge(rule.ruleId()));

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_occurrence
                        WHERE rule_id = ? AND status = 'CANCELED'
                        """,
                        Long.class,
                        rule.ruleId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_rule
                        WHERE id = ? AND status = 'ACKNOWLEDGED'
                        """,
                        Long.class,
                        rule.ruleId()))
                .isEqualTo(1L);
    }

    private TestScope seedCalendar(String planKey, boolean critical) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, planKey);
        WorkspaceContext context =
                new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
        Instant nodeTime =
                Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MICROS);
        CalendarTimeNode node = critical
                ? CalendarTimeNode.relativeToOwner(
                        "anchor",
                        "主節點",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ZERO,
                        com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality.CRITICAL,
                        com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability.LOCKED)
                : CalendarTimeNode.absolute("anchor", "主節點", nodeTime);
        inContext(
                context,
                () -> calendars.createPlan(new CreateCalendarPlanCommand(
                        planKey,
                        "提醒測試",
                        CalendarPlacement.point(nodeTime, ZoneId.of("Asia/Taipei")),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(CalendarNodeDraft.of(node)))));
        return new TestScope(context, workspace, planKey, nodeTime);
    }

    private long count(String table, UUID workspaceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspaceId);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
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

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void inContext(WorkspaceContext context, Runnable work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            work.run();
        }
    }

    private record TestScope(
            WorkspaceContext context, UUID workspaceId, String planKey, Instant nodeTime) {}
}

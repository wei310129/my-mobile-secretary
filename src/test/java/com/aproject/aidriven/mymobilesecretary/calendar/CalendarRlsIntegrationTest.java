package com.aproject.aidriven.mymobilesecretary.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarActivityRepository activities;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname = 'mms_calendar_rls_runtime') THEN
                        CREATE ROLE mms_calendar_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void applicationFilterAndRuntimeRoleFailClosedAcrossActorsWorkspacesAndBackground() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seed(owner, workspace, "owner");
        seedPeer(peer, owner, workspace, "viewer/editor peer");
        seed(outsider, otherWorkspace, "outsider");
        UUID planId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        WorkspaceContext ownerContext = context(owner, workspace);
        inContext(ownerContext, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "私人核心行程",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(ownerContext, () -> activities.saveAndFlush(CalendarActivityEntity.create(
                activityId,
                planId,
                "子活動",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        UUID nodeId = UUID.randomUUID();
        inContext(ownerContext, () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                nodeId,
                planId,
                activityId,
                CalendarTimeNode.relativeToOwner(
                        "queue",
                        "排隊",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ofMinutes(-10),
                        Criticality.NORMAL,
                        Adjustability.FLEXIBLE),
                NOW)));
        UUID ruleId = UUID.randomUUID();
        runtime(ownerContext, () -> jdbc.update(
                """
                INSERT INTO calendar_reminder_rule (
                    id, plan_id, node_id, owner_kind, rule_kind, offset_seconds,
                    delivery_mode, status, revision, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, 'PERSONAL', 'RELATIVE', 0,
                    'ONCE', 'ACTIVE', 1, 0, ?, ?, ?, ?, ?)
                """,
                ruleId,
                planId,
                nodeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                workspace,
                owner,
                owner));
        UUID adoptionId = UUID.randomUUID();
        runtime(ownerContext, () -> jdbc.update(
                """
                INSERT INTO calendar_adoption (
                    id, plan_id, status, revision, version, created_at, updated_at,
                    workspace_id, created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, 'ACTIVE', 1, 0, ?, ?, ?, ?, ?)
                """,
                adoptionId,
                planId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                workspace,
                owner,
                owner));
        runtime(ownerContext, () -> jdbc.update(
                """
                INSERT INTO calendar_adoption_node (
                    adoption_id, plan_id, node_id, node_revision, created_at,
                    workspace_id, created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """,
                adoptionId,
                planId,
                nodeId,
                Timestamp.from(NOW),
                workspace,
                owner,
                owner));
        runtime(ownerContext, () -> jdbc.update(
                """
                INSERT INTO calendar_reminder_occurrence (
                    id, rule_id, plan_id, node_id, node_revision, rule_revision,
                    sequence_number, scheduled_at, status, version,
                    created_at, updated_at, workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, 1, 1, 0, ?, 'PENDING', 0, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ruleId,
                planId,
                nodeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                workspace,
                owner,
                owner));

        assertThat(inContext(context(peer, workspace), () -> plans
                        .findByIdAndWorkspaceIdAndCreatedByUserId(planId, workspace, peer)))
                .isEmpty();
        assertThat(inContext(context(outsider, otherWorkspace), () -> plans
                        .findByIdAndWorkspaceIdAndCreatedByUserId(
                                planId, otherWorkspace, outsider)))
                .isEmpty();
        assertThat(runtime(context(peer, workspace), () -> jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_plan", Long.class)))
                .isZero();
        assertThat(runtime(ownerContext, () -> counts()))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 1L, 1L);
        assertThat(runtime(context(peer, workspace), this::counts))
                .containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        assertThat(runtime(context(outsider, otherWorkspace), () -> jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_plan", Long.class)))
                .isZero();
        assertThat(runtime(WorkspaceContext.system(), this::counts))
                .containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        assertThat(runtime(context(peer, workspace), () -> jdbc.update(
                        "UPDATE calendar_plan SET title = '越權修改' WHERE id = ?", planId)))
                .isZero();
        assertThat(runtime(context(peer, workspace), () -> jdbc.update(
                        "DELETE FROM calendar_plan WHERE id = ?", planId)))
                .isZero();
        assertThatThrownBy(() -> runtime(context(peer, workspace), () -> jdbc.update(
                        """
                        INSERT INTO calendar_plan (
                            id, title, placement_kind, timed_start, zone_id, version,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, '越權建立', 'TIMED_POINT', ?, 'Asia/Taipei', 0,
                            ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        NOW,
                        NOW,
                        NOW,
                        workspace,
                        owner)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void optimisticVersionRejectsAStaleEntityUpdate() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "version owner");
        WorkspaceContext context = context(actor, workspace);
        UUID planId = UUID.randomUUID();
        inContext(context, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "原始",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));

        CalendarPlanEntity first = inContext(context, () -> plans
                .findByIdAndWorkspaceIdAndCreatedByUserId(planId, workspace, actor)
                .orElseThrow());
        CalendarPlanEntity second = inContext(context, () -> plans
                .findByIdAndWorkspaceIdAndCreatedByUserId(planId, workspace, actor)
                .orElseThrow());
        first.rename("第一版", NOW.plusSeconds(1));
        inContext(context, () -> plans.saveAndFlush(first));
        second.rename("過期版本", NOW.plusSeconds(2));

        assertThatThrownBy(() -> inContext(context, () -> plans.saveAndFlush(second)))
                .isInstanceOf(RuntimeException.class);
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

    private void seedPeer(UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private java.util.List<Long> counts() {
        return java.util.List.of(
                jdbc.queryForObject("SELECT count(*) FROM calendar_plan", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM calendar_activity", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM calendar_time_node", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM calendar_reminder_rule", Long.class),
                jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_reminder_occurrence",
                        Long.class),
                jdbc.queryForObject("SELECT count(*) FROM calendar_adoption", Long.class),
                jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_adoption_node", Long.class));
    }
}

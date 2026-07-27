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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarPersistenceIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarActivityRepository activities;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsPlanActivityAndMultipleTypedNodesWithCompositeOwnership() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "owner");
        WorkspaceContext context = context(actor, workspace);
        UUID planId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID planNodeId = UUID.randomUUID();
        UUID activityNodeId = UUID.randomUUID();
        UUID relativeNodeId = UUID.randomUUID();

        inContext(context, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "裕隆城",
                CalendarPlacement.interval(
                        Instant.parse("2026-07-25T03:00:00Z"),
                        Instant.parse("2026-07-25T08:00:00Z"),
                        ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(context, () -> activities.saveAndFlush(CalendarActivityEntity.create(
                activityId,
                planId,
                "電影",
                CalendarPlacement.point(
                        Instant.parse("2026-07-25T03:30:00Z"), ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(context, () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                planNodeId,
                planId,
                null,
                CalendarTimeNode.absolute(
                        "departure", "離港", Instant.parse("2026-07-25T05:00:00Z")),
                NOW)));
        inContext(context, () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                activityNodeId,
                planId,
                activityId,
                CalendarTimeNode.relativeToOwner(
                        "queue",
                        "排隊",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ofMinutes(-10),
                        Criticality.CRITICAL,
                        Adjustability.FLEXIBLE),
                NOW)));
        inContext(context, () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                relativeNodeId,
                planId,
                activityId,
                CalendarTimeNode.relativeToNode(
                        "check",
                        "確認票券",
                        "departure",
                        Duration.ofMinutes(-5),
                        Criticality.NORMAL,
                        Adjustability.LOCKED),
                NOW)));

        assertThat(inContext(context, () -> plans
                        .findByIdAndWorkspaceIdAndCreatedByUserId(planId, workspace, actor)
                        .orElseThrow()
                        .toPlacement()))
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-07-25T03:00:00Z"),
                        Instant.parse("2026-07-25T08:00:00Z"),
                        ZoneId.of("Asia/Taipei")));
        assertThat(inContext(context, () -> nodes
                        .findAllByPlanIdAndWorkspaceIdAndCreatedByUserIdOrderByCreatedAt(
                                planId, workspace, actor)))
                .hasSize(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_activity WHERE plan_id = ?",
                        Long.class,
                        planId))
                .isEqualTo(1L);
        assertThatThrownBy(() -> inContext(context, () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                planId,
                                null,
                                CalendarTimeNode.absolute(
                                        "departure",
                                        "重複離港",
                                        Instant.parse("2026-07-25T06:00:00Z")),
                                NOW))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void compositeForeignKeysRejectCrossPlanAndCrossActivityBinding() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "fk owner");
        UUID firstPlan = UUID.randomUUID();
        UUID secondPlan = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        WorkspaceContext context = context(actor, workspace);
        inContext(context, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                firstPlan,
                "第一個",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(context, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                secondPlan,
                "第二個",
                CalendarPlacement.point(NOW.plusSeconds(3600), ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(context, () -> activities.saveAndFlush(CalendarActivityEntity.create(
                activity,
                firstPlan,
                "只屬於第一個",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO calendar_time_node (
                            id, plan_id, activity_id, node_key, label, expression_kind,
                            absolute_time, criticality, adjustability, version,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, ?, ?, 'cross-plan', 'cross-plan', 'ABSOLUTE',
                            ?, 'NORMAL', 'LOCKED', 0, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        secondPlan,
                        activity,
                        NOW,
                        NOW,
                        NOW,
                        workspace,
                        actor))
                .isInstanceOf(DataAccessException.class);
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

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }
}

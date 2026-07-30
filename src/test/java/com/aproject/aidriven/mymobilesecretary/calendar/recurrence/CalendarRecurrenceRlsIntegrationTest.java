package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarAdoptionService;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferAcceptance;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferOffer;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarRecurrenceRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_recurrence_rls_runtime";
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarActivityRepository activities;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private CalendarAdoptionService adoptions;
    @Autowired private CalendarRecurrenceProjectionService recurrenceProjections;
    @Autowired private CalendarOwnershipTransferService ownershipTransfers;
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
                        WHERE rolname = 'mms_calendar_recurrence_rls_runtime') THEN
                        CREATE ROLE mms_calendar_recurrence_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE"
                        + " ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void mutationFollowsEffectiveOwnerWhileSourceIdentityRemainsImmutable() {
        UUID sourceOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(sourceOwner, workspace, "source owner");
        seedPeer(nextOwner, sourceOwner, workspace, "next owner");
        seedPeer(outsider, sourceOwner, workspace, "outsider");
        UUID planId = UUID.randomUUID();
        inContext(context(sourceOwner, workspace), () -> plans.saveAndFlush(
                CalendarPlanEntity.create(
                        planId,
                        "recurring plan",
                        CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                        NOW)));
        UUID seriesId = UUID.randomUUID();

        assertThat(runtime(
                        context(sourceOwner, workspace),
                        () -> insertSeries(seriesId, planId, workspace, sourceOwner)))
                .isEqualTo(1);
        assertThat(runtime(
                        context(outsider, workspace),
                        () -> countSeries(seriesId)))
                .isZero();
        assertThatThrownBy(() -> runtime(
                        context(outsider, workspace),
                        () -> insertSeries(
                                UUID.randomUUID(), planId, workspace, outsider)))
                .isInstanceOf(RuntimeException.class);

        var offered = runtime(
                context(sourceOwner, workspace),
                () -> ownershipTransfers.offer(new CalendarOwnershipTransferOffer(
                        "recurrence-owner-offer",
                        planId,
                        nextOwner,
                        1,
                        Duration.ofHours(1))));
        runtime(
                context(nextOwner, workspace),
                () -> ownershipTransfers.accept(new CalendarOwnershipTransferAcceptance(
                        "recurrence-owner-accept", offered.id(), offered.revision(), 1)));

        assertThat(runtime(
                        context(sourceOwner, workspace),
                        () -> countSeries(seriesId)))
                .isZero();
        assertThat(runtime(
                        context(nextOwner, workspace),
                        () -> countSeries(seriesId)))
                .isEqualTo(1L);
        assertThat(runtime(
                        context(nextOwner, workspace),
                        () -> jdbc.update(
                                "UPDATE calendar_recurrence_series"
                                        + " SET updated_at = CURRENT_TIMESTAMP"
                                        + " WHERE id = ?",
                                seriesId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT source_created_by_user_id
                        FROM calendar_recurrence_series
                        WHERE id = ?
                        """,
                        UUID.class,
                        seriesId))
                .isEqualTo(sourceOwner);
    }

    @Test
    void recurrenceProjectionStateIsRevisionBoundAndActorPrivate() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "projection owner");
        seedPeer(peer, owner, workspace, "projection peer");
        UUID planId = UUID.randomUUID();
        WorkspaceContext ownerContext = context(owner, workspace);
        inContext(ownerContext, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "recurrence projection",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(ownerContext, () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                UUID.randomUUID(),
                planId,
                null,
                CalendarTimeNode.absolute("start", "start", NOW),
                NOW)));
        inContext(ownerContext, () -> adoptions.adoptPlan(
                planId, java.util.List.of("start")));
        UUID adoptionId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_adoption
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                """,
                UUID.class,
                planId,
                workspace,
                owner);
        UUID seriesId = UUID.randomUUID();
        seedSeriesRevision(seriesId, planId, workspace, owner);

        var recurrenceAdoption = inContext(
                ownerContext,
                () -> recurrenceProjections.adoptSeries(
                        "series-adoption", adoptionId, planId, seriesId, 1));
        var adoptionReplay = inContext(
                ownerContext,
                () -> recurrenceProjections.adoptSeries(
                        "series-adoption", adoptionId, planId, seriesId, 1));
        assertThat(adoptionReplay).isEqualTo(recurrenceAdoption);
        assertThat(runtime(
                        context(peer, workspace),
                        () -> count(
                                "calendar_recurrence_adoption",
                                recurrenceAdoption.id())))
                .isZero();

        var key =
                CalendarOccurrenceKey.timed(java.time.LocalDateTime.of(2026, 8, 5, 19, 0));
        var skipped = inContext(
                ownerContext,
                () -> recurrenceProjections.setOccurrenceParticipation(
                        "skip-once", planId, seriesId, 1, key, true));
        var skipReplay = inContext(
                ownerContext,
                () -> recurrenceProjections.setOccurrenceParticipation(
                        "skip-once", planId, seriesId, 1, key, true));
        assertThat(skipReplay).isEqualTo(skipped);
        assertThat(skipped.skipped()).isTrue();
        assertThat(runtime(
                        context(peer, workspace),
                        () -> count(
                                "calendar_recurrence_participation_exception",
                                skipped.id())))
                .isZero();
        var restored = inContext(
                ownerContext,
                () -> recurrenceProjections.setOccurrenceParticipation(
                        "restore-once", planId, seriesId, 1, key, false));
        assertThat(restored.id()).isEqualTo(skipped.id());
        assertThat(restored.skipped()).isFalse();
        assertThat(restored.revision()).isEqualTo(2);
        assertThatThrownBy(() -> inContext(
                        ownerContext,
                        () -> recurrenceProjections.setOccurrenceParticipation(
                                "restore-once",
                                planId,
                                seriesId,
                                1,
                                CalendarOccurrenceKey.timed(
                                        java.time.LocalDateTime.of(
                                                2026, 8, 6, 19, 0)),
                                false)))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime(
                        ownerContext,
                        () -> count(
                                "calendar_recurrence_adoption",
                                recurrenceAdoption.id())))
                .isEqualTo(1L);
    }

    @Test
    void recurrenceAncestryAllowsAPlanOrChildOwnerButNeverBoth() {
        UUID owner = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "ancestry owner");
        UUID planId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        WorkspaceContext ownerContext = context(owner, workspace);
        inContext(ownerContext, () -> plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "plain plan",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        inContext(ownerContext, () -> activities.saveAndFlush(CalendarActivityEntity.create(
                activityId,
                planId,
                "recurring child",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW)));
        UUID childSeries = UUID.randomUUID();
        assertThat(runtime(
                        ownerContext,
                        () -> insertSeries(
                                childSeries, planId, activityId, workspace, owner)))
                .isEqualTo(1);

        assertThatThrownBy(() -> runtime(
                        ownerContext,
                        () -> insertSeries(
                                UUID.randomUUID(), planId, null, workspace, owner)))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime(
                        ownerContext,
                        () -> count("calendar_recurrence_series", childSeries)))
                .isEqualTo(1L);
    }

    private int insertSeries(
            UUID seriesId, UUID planId, UUID workspaceId, UUID sourceOwner) {
        return insertSeries(seriesId, planId, null, workspaceId, sourceOwner);
    }

    private int insertSeries(
            UUID seriesId,
            UUID planId,
            UUID activityId,
            UUID workspaceId,
            UUID sourceOwner) {
        return jdbc.update(
                """
                INSERT INTO calendar_recurrence_series (
                    id, plan_id, activity_id, lineage_root_id, active_revision,
                    created_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                planId,
                activityId,
                seriesId,
                workspaceId,
                sourceOwner);
    }

    private void seedSeriesRevision(
            UUID seriesId, UUID planId, UUID workspaceId, UUID sourceOwner) {
        insertSeries(seriesId, planId, workspaceId, sourceOwner);
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_rule_revision (
                    series_id, revision, frequency, recurrence_interval,
                    timed_anchor, duration_seconds, zone_id,
                    end_kind, effective_from_timed, state,
                    request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, 1, 'DAILY', 1, ?, 3600, 'Asia/Taipei',
                    'UNBOUNDED', ?, 'ACTIVE', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                java.sql.Timestamp.valueOf("2026-08-01 19:00:00"),
                java.sql.Timestamp.valueOf("2026-08-01 19:00:00"),
                "e".repeat(64),
                "f".repeat(64),
                sourceOwner,
                workspaceId,
                sourceOwner);
    }

    private long countSeries(UUID seriesId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_recurrence_series WHERE id = ?",
                Long.class,
                seriesId);
    }

    private long count(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?",
                Long.class,
                id);
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
}

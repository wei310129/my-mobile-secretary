package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferAcceptance;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferOffer;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOwnershipTransferService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
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

    private int insertSeries(
            UUID seriesId, UUID planId, UUID workspaceId, UUID sourceOwner) {
        return jdbc.update(
                """
                INSERT INTO calendar_recurrence_series (
                    id, plan_id, lineage_root_id, active_revision,
                    created_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                seriesId,
                planId,
                seriesId,
                workspaceId,
                sourceOwner);
    }

    private long countSeries(UUID seriesId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_recurrence_series WHERE id = ?",
                Long.class,
                seriesId);
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

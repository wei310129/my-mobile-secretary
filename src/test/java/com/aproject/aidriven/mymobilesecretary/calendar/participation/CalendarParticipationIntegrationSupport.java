package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarAdoptionService;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjectionService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeCapabilityService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeMutationService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

abstract class CalendarParticipationIntegrationSupport extends IntegrationTestBase {

    static final String RUNTIME_ROLE = "mms_calendar_participation_runtime";
    static final Instant START = Instant.parse("2026-07-27T01:00:00Z");

    @Autowired CalendarApplicationService calendars;
    @Autowired CalendarAdoptionService adoptions;
    @Autowired PersonalRouteProjectionService routes;
    @Autowired CalendarShareService shares;
    @Autowired CalendarParticipationService participations;
    @Autowired CalendarParticipationPolicyService participationPolicies;
    @Autowired CalendarWatchSubscriptionService watches;
    @Autowired CalendarSkipService skips;
    @Autowired CalendarPersonalProjectionProcessor personalProjections;
    @Autowired CalendarAuthoritativeCapabilityService capabilities;
    @Autowired CalendarAuthoritativeMutationService authoritativeMutations;
    @Autowired CalendarReminderApplicationService calendarReminders;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname = 'mms_calendar_participation_runtime') THEN
                        CREATE ROLE mms_calendar_participation_runtime
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

    Fixture fixture() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "participation owner");
        seedUser(recipient, "participation recipient");
        seedUser(admin, "participation admin");
        seedUser(peer, "participation peer");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'participation household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        addMember(workspace, admin, owner, "ADMIN");
        addMember(workspace, peer, owner, "MEMBER");
        WorkspaceContext ownerContext = context(owner, workspace);
        String requestKey =
                "participation-plan-" + UUID.randomUUID();
        UUID planId = inContext(ownerContext, () -> calendars
                .createPlanWithIdentity(new CreateCalendarPlanCommand(
                        requestKey,
                        "群組登船行程",
                        CalendarPlacement.interval(
                                START,
                                START.plusSeconds(3600),
                                ZoneId.of("Asia/Taipei")),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(CalendarNodeDraft.of(
                                CalendarTimeNode.absolute(
                                        "boarding", "登船", START)))))
                .planId());
        UUID nodeId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_time_node
                WHERE plan_id = ? AND node_key = 'boarding'
                """,
                UUID.class,
                planId);
        return new Fixture(
                planId,
                nodeId,
                requestKey,
                ownerContext,
                context(recipient, workspace),
                context(admin, workspace),
                context(peer, workspace));
    }

    WorkspaceContext addActor(Fixture fixture, String label) {
        UUID actor = UUID.randomUUID();
        seedUser(actor, label);
        addMember(
                fixture.ownerContext().workspaceId(),
                actor,
                fixture.ownerContext().actorId(),
                "MEMBER");
        return context(actor, fixture.ownerContext().workspaceId());
    }

    void grantWholePlanViewer(
            Fixture fixture, WorkspaceContext recipient, String requestKey) {
        inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        requestKey, fixture.planId(), recipient.actorId(), 1));
    }

    CalendarParticipationScope planScope(Fixture fixture) {
        return new CalendarParticipationScope(
                CalendarParticipationScopeType.PLAN, fixture.planId());
    }

    void changeParticipation(
            Fixture fixture,
            WorkspaceContext actor,
            CalendarParticipationStatus status,
            String requestKey,
            long expectedRevision) {
        runtime(
                actor,
                () -> participations.change(new CalendarParticipationChange(
                        requestKey,
                        fixture.planId(),
                        planScope(fixture),
                        status,
                        expectedRevision)));
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    long runtimeCount(
            WorkspaceContext context, String table, UUID planId) {
        return runtime(
                context,
                () -> jdbc.queryForObject(
                        "SELECT count(*) FROM "
                                + table
                                + " WHERE plan_id = ?",
                        Long.class,
                        planId));
    }

    <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void seedUser(UUID id, String name) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                name);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    record Fixture(
            UUID planId,
            UUID nodeId,
            String requestKey,
            WorkspaceContext ownerContext,
            WorkspaceContext recipientContext,
            WorkspaceContext adminContext,
            WorkspaceContext peerContext) {}
}

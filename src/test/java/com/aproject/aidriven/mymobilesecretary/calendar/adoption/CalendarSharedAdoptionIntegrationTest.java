package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

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
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarSharedAdoptionIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_shared_adoption_runtime";
    private static final Instant START = Instant.parse("2026-07-26T01:00:00Z");

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarAdoptionService adoptions;
    @Autowired private PersonalRouteProjectionService projection;
    @Autowired private CalendarShareService shares;
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
                        WHERE rolname = 'mms_calendar_shared_adoption_runtime') THEN
                        CREATE ROLE mms_calendar_shared_adoption_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE"
                        + " ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void viewerVisibilityDoesNotAffectRoutesUntilRecipientAdoptsSelectedNode() {
        Fixture fixture = createFixture();
        inContext(fixture.ownerContext(), () -> {
            shares.createViewerShare(
                    "shared-adoption-viewer",
                    fixture.planId(),
                    fixture.recipientContext().actorId(),
                    1);
            return null;
        });

        inContext(fixture.recipientContext(), () -> {
            assertThat(adoptions.constraints()).isEmpty();
            assertThat(projection.current().busyIntervals()).isEmpty();
            assertThat(projection.current().routeConstraints()).isEmpty();

            adoptions.adoptPlan(fixture.planId(), List.of("shared-selected"));

            assertThat(adoptions.constraints())
                    .extracting(PersonalRouteConstraint::nodeKey)
                    .containsExactly("shared-selected");
            assertThat(projection.current().routeConstraints())
                    .extracting(PersonalRouteConstraint::nodeKey)
                    .containsExactly("shared-selected");
            assertThat(projection.current().busyIntervals())
                    .extracting(CalendarBusyInterval::title)
                    .containsExactly("共享採用行程");
            return null;
        });

        inContext(fixture.ownerContext(), () -> {
            assertThat(adoptions.constraints()).isEmpty();
            assertThat(projection.current().busyIntervals()).isEmpty();
            assertThat(projection.current().routeConstraints()).isEmpty();
            return null;
        });

        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> updatePlan(fixture.planId())))
                .isZero();
        for (UUID nodeId : fixture.nodeIds()) {
            assertThat(runtime(
                            fixture.recipientContext(),
                            () -> updateNode(nodeId)))
                    .isZero();
        }
    }

    @Test
    void unsharedWorkspaceAdminAndPeerFailClosedWithoutAdoptionMutation() {
        Fixture fixture = createFixture();
        long before = adoptionMutationCount();

        assertThatThrownBy(() -> inContext(
                        fixture.adminContext(),
                        () -> adoptions.adoptPlan(
                                fixture.planId(), List.of("shared-selected"))))
                .isInstanceOfAny(
                        SecurityException.class,
                        BusinessException.class,
                        NotFoundException.class);
        assertThatThrownBy(() -> inContext(
                        fixture.peerContext(),
                        () -> adoptions.adoptPlan(
                                fixture.planId(), List.of("shared-selected"))))
                .isInstanceOfAny(
                        SecurityException.class,
                        BusinessException.class,
                        NotFoundException.class);

        assertThat(adoptionMutationCount()).isEqualTo(before);
    }

    private Fixture createFixture() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedHousehold(owner, recipient, admin, peer, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> calendars
                .createPlanWithIdentity(new CreateCalendarPlanCommand(
                        "shared-adoption-plan",
                        "共享採用行程",
                        CalendarPlacement.interval(
                                START,
                                START.plusSeconds(3600),
                                ZoneId.of("Asia/Taipei")),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                                CalendarNodeDraft.of(CalendarTimeNode.absolute(
                                        "shared-selected", "採用節點", START)),
                                CalendarNodeDraft.of(CalendarTimeNode.absolute(
                                        "shared-not-selected",
                                        "未採用節點",
                                        START.plusSeconds(1800))))))
                .planId());
        List<UUID> nodeIds = jdbc.query(
                """
                SELECT id FROM calendar_time_node
                WHERE plan_id = ?
                ORDER BY node_key
                """,
                (row, ignored) -> row.getObject("id", UUID.class),
                planId);
        UUID selected = jdbc.queryForObject(
                """
                SELECT id FROM calendar_time_node
                WHERE plan_id = ? AND node_key = 'shared-selected'
                """,
                UUID.class,
                planId);
        List<UUID> orderedNodeIds = List.of(
                selected,
                nodeIds.stream()
                        .filter(id -> !id.equals(selected))
                        .findFirst()
                        .orElseThrow());
        return new Fixture(
                planId,
                orderedNodeIds,
                ownerContext,
                context(recipient, workspace),
                context(admin, workspace),
                context(peer, workspace));
    }

    private long adoptionMutationCount() {
        return jdbc.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM calendar_adoption)
                    + (SELECT count(*) FROM calendar_adoption_node)
                """,
                Long.class);
    }

    private int updatePlan(UUID planId) {
        return jdbc.update(
                "UPDATE calendar_plan SET updated_at = updated_at WHERE id = ?",
                planId);
    }

    private int updateNode(UUID nodeId) {
        return jdbc.update(
                """
                UPDATE calendar_time_node
                SET updated_at = updated_at
                WHERE id = ?
                """,
                nodeId);
    }

    private void seedHousehold(
            UUID owner,
            UUID recipient,
            UUID admin,
            UUID peer,
            UUID workspace) {
        seedUser(owner, "shared adoption owner");
        seedUser(recipient, "shared adoption recipient");
        seedUser(admin, "shared adoption admin");
        seedUser(peer, "shared adoption peer");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'shared adoption household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        addMember(workspace, admin, owner, "ADMIN");
        addMember(workspace, peer, owner, "MEMBER");
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

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private record Fixture(
            UUID planId,
            List<UUID> nodeIds,
            WorkspaceContext ownerContext,
            WorkspaceContext recipientContext,
            WorkspaceContext adminContext,
            WorkspaceContext peerContext) {}
}

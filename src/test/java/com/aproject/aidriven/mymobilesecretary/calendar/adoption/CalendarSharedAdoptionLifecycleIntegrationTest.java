package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarSharedAdoptionLifecycleIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_shared_adoption_runtime";
    private static final Instant START = Instant.parse("2026-08-10T01:00:00Z");

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarAdoptionService adoptions;
    @Autowired private CalendarShareService shares;
    @Autowired private CalendarPlanLifecycleService lifecycle;
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

    @ParameterizedTest
    @EnumSource(LifecycleAction.class)
    void ownerLifecycleCancelsEveryActorsAdoptionWithoutDeletingPrivateHistoryOrSources(
            LifecycleAction action) {
        Household household = seedHousehold();
        WorkspaceContext owner = context(household.owner(), household.workspace());
        WorkspaceContext recipient =
                context(household.recipient(), household.workspace());
        WorkspaceContext peer = context(household.peer(), household.workspace());
        String suffix = action.name().toLowerCase();

        UUID sharedPlan = inContext(owner, () -> createPlan(
                "shared-adoption-" + suffix, "共享採用 " + suffix));
        inContext(owner, () -> adoptions.adopt(
                "shared-adoption-" + suffix, List.of("anchor")));
        inContext(owner, () -> shares.createViewerShare(
                "shared-adoption-share-" + suffix,
                sharedPlan,
                household.recipient(),
                1));
        inContext(recipient, () -> adoptions.adoptPlan(
                sharedPlan, List.of("anchor")));

        UUID unrelatedPlan = inContext(peer, () -> createPlan(
                "unrelated-adoption-" + suffix, "其他人的採用 " + suffix));
        inContext(peer, () -> adoptions.adopt(
                "unrelated-adoption-" + suffix, List.of("anchor")));

        assertThat(countAdoptions(sharedPlan)).isEqualTo(2);
        assertThat(runtime(owner, () -> countVisibleAdoptions(sharedPlan)))
                .as("owner must not SELECT the recipient's private adoption")
                .isEqualTo(1);
        assertThat(runtime(recipient, () -> countVisibleAdoptions(sharedPlan)))
                .isEqualTo(1);

        long adoptionRows = count("calendar_adoption", sharedPlan);
        long historyRows = count("calendar_adoption_history", sharedPlan);
        long selectedNodeRows = count("calendar_adoption_node", sharedPlan);
        long sourceNodeRows = count("calendar_time_node", sharedPlan);
        assertThat(historyRows).isPositive();

        inContext(owner, () -> {
            action.apply(lifecycle, sharedPlan);
            return null;
        });

        assertThat(statuses(sharedPlan))
                .hasSize(2)
                .allMatch("CANCELED"::equals);
        assertThat(count("calendar_adoption", sharedPlan)).isEqualTo(adoptionRows);
        assertThat(count("calendar_adoption_history", sharedPlan))
                .isGreaterThanOrEqualTo(historyRows);
        assertThat(count("calendar_adoption_node", sharedPlan))
                .isEqualTo(selectedNodeRows);
        assertThat(count("calendar_time_node", sharedPlan)).isEqualTo(sourceNodeRows);
        assertThat(countPlan(sharedPlan)).isEqualTo(1);
        assertThat(statuses(unrelatedPlan)).containsExactly("ACTIVE");
        assertThat(runtime(owner, () -> countVisibleAdoptions(sharedPlan)))
                .as("lifecycle authority must not widen owner read visibility")
                .isEqualTo(1);
    }

    private UUID createPlan(String key, String title) {
        calendars.createPlan(new CreateCalendarPlanCommand(
                key,
                title,
                CalendarPlacement.point(START, ZoneId.of("Asia/Taipei")),
                "共享",
                null,
                null,
                List.of(),
                List.of(CalendarNodeDraft.of(
                        CalendarTimeNode.absolute("anchor", "集合", START)))));
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return jdbc.queryForObject(
                """
                SELECT id FROM calendar_plan
                WHERE workspace_id = ? AND created_by_user_id = ? AND title = ?
                """,
                UUID.class,
                context.workspaceId(),
                context.actorId(),
                title);
    }

    private long countAdoptions(UUID planId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_adoption WHERE plan_id = ?",
                Long.class,
                planId);
    }

    private long countVisibleAdoptions(UUID planId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_adoption WHERE plan_id = ?",
                Long.class,
                planId);
    }

    private long count(String table, UUID planId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE plan_id = ?",
                Long.class,
                planId);
    }

    private long countPlan(UUID planId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_plan WHERE id = ?",
                Long.class,
                planId);
    }

    private List<String> statuses(UUID planId) {
        return jdbc.queryForList(
                """
                SELECT effective_status
                FROM calendar_adoption_effective_state
                WHERE plan_id = ? ORDER BY created_by_user_id
                """,
                String.class,
                planId);
    }

    private Household seedHousehold() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "shared adoption owner");
        seedUser(recipient, "shared adoption recipient");
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
        addMember(workspace, peer, owner, "MEMBER");
        return new Household(owner, recipient, peer, workspace);
    }

    private void seedUser(UUID id, String displayName) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                displayName);
    }

    private void addMember(
            UUID workspace, UUID member, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                member,
                role,
                creator);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
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

    private enum LifecycleAction {
        CANCEL {
            @Override
            void apply(
                    CalendarPlanLifecycleService lifecycle,
                    UUID planId) {
                lifecycle.cancel(planId, 1);
            }
        },
        ARCHIVE {
            @Override
            void apply(
                    CalendarPlanLifecycleService lifecycle,
                    UUID planId) {
                lifecycle.archive(planId, 1);
            }
        };

        abstract void apply(
                CalendarPlanLifecycleService lifecycle,
                UUID planId);
    }

    private record Household(
            UUID owner, UUID recipient, UUID peer, UUID workspace) {}
}

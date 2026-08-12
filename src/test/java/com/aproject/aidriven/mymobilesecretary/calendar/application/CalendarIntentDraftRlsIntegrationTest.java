package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarIntentDraftRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_calendar_draft_rls_runtime";

    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private RouteOperationPreferenceService routeOperationPreferences;
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
                        WHERE rolname = 'mms_calendar_draft_rls_runtime') THEN
                        CREATE ROLE mms_calendar_draft_rls_runtime
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
    void runtimeRoleShowsOnlyActorRowsAndRejectsPeerMutation() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "owner");
        seedUser(peer, "peer");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'rls workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        WorkspaceContext ownerContext = context(owner, workspace);
        WorkspaceContext peerContext = context(peer, workspace);
        UUID ownerDraft = in(ownerContext, () -> drafts.propose(command("owner proposal"))).id();
        UUID peerDraft = in(peerContext, () -> drafts.propose(command("peer proposal"))).id();
        in(ownerContext, () -> routeOperationPreferences.setGeneral(10, 15));
        in(peerContext, () -> routeOperationPreferences.setGeneral(20, 25));

        assertThat(runtime(ownerContext, this::visibleDraftIds)).containsExactly(ownerDraft);
        assertThat(runtime(ownerContext, this::visibleRouteOperationRevisions))
                .containsExactly(2L);
        assertThat(runtime(peerContext, this::visibleRouteOperationRevisions))
                .containsExactly(2L);
        assertThat(runtime(WorkspaceContext.system(), this::visibleRouteOperationRevisions))
                .isEmpty();
        assertThat(runtime(peerContext, () -> jdbc.update(
                        """
                        UPDATE actor_route_operation_preference
                        SET parking_minutes = 99
                        WHERE workspace_id = ? AND created_by_user_id = ?
                        """,
                        workspace,
                        owner)))
                .isZero();
        assertThat(runtime(peerContext, this::visibleDraftIds)).containsExactly(peerDraft);
        assertThat(runtime(WorkspaceContext.system(), this::visibleDraftIds)).isEmpty();
        assertThat(runtime(peerContext, () -> jdbc.update(
                        "UPDATE calendar_intent_draft SET title = 'stolen' WHERE id = ?",
                        ownerDraft)))
                .isZero();
        assertThat(runtime(ownerContext, this::visibleDraftIds)).containsExactly(ownerDraft);
    }

    private List<UUID> visibleDraftIds() {
        return jdbc.queryForList(
                "SELECT id FROM calendar_intent_draft ORDER BY id", UUID.class);
    }

    private List<Long> visibleRouteOperationRevisions() {
        return jdbc.queryForList(
                "SELECT revision FROM actor_route_operation_preference ORDER BY id", Long.class);
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private <T> T in(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void seedUser(UUID actor, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor,
                label + actor.toString().substring(0, 6));
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST, "h4-rls", "scope");
    }

    private static IntentCommand command(String title) {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                "2026-09-10T15:00:00+08:00",
                "2026-09-10T16:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty(),
                "9月10日下午三點到四點" + title);
    }
}

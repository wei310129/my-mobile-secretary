package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkspaceRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_rls_test_runtime";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createNonBypassRuntimeRole() {
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mms_rls_test_runtime') THEN
                        CREATE ROLE mms_rls_test_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + RUNTIME_ROLE);
        jdbcTemplate.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "
                + RUNTIME_ROLE);
    }

    @Test
    void transactionScopeDrivesDatabaseEnforcedWorkspaceAndActorIsolation() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seedAccount(firstActor, firstWorkspace, "First");
        seedAccount(secondActor, secondWorkspace, "Second");
        UUID householdPeer = UUID.randomUUID();
        seedUser(householdPeer, "Peer");

        long firstItem = insertItem(firstWorkspace, firstActor, "First item");
        long secondItem = insertItem(secondWorkspace, secondActor, "Second item");
        insertLineMessage(firstWorkspace, firstActor, "first private message");
        insertLineMessage(firstWorkspace, householdPeer, "peer private message");

        WorkspaceContext first = new WorkspaceContext(
                firstActor, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext second = new WorkspaceContext(
                secondActor, secondWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext peer = new WorkspaceContext(
                householdPeer, firstWorkspace, WorkspaceChannel.TEST);
        WorkspaceContext integration = new WorkspaceContext(
                firstActor, firstWorkspace, WorkspaceChannel.INTEGRATION);

        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList("SELECT id FROM item ORDER BY id", Long.class)))
                .containsExactly(firstItem);
        assertThat(inRuntimeTransaction(second,
                () -> jdbcTemplate.queryForList("SELECT id FROM item ORDER BY id", Long.class)))
                .containsExactly(secondItem);
        assertThat(inRuntimeTransaction(first,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("first private message");
        assertThat(inRuntimeTransaction(peer,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("peer private message");
        assertThat(inRuntimeTransaction(integration,
                () -> jdbcTemplate.queryForList(
                        "SELECT content FROM line_message_log ORDER BY id", String.class)))
                .containsExactly("first private message");

        assertThat(inRuntimeTransaction(WorkspaceContext.authentication(),
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM item", Long.class)))
                .isZero();
        assertThat(inRuntimeTransaction(WorkspaceContext.system(),
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM item", Long.class)))
                .isZero();

        assertThatThrownBy(() -> inRuntimeTransaction(first, () -> {
            jdbcTemplate.update("""
                    INSERT INTO item (
                        name, created_at, inventory_quantity, shopping_needed, updated_at,
                        workspace_id, created_by_user_id)
                    VALUES ('cross-tenant write', CURRENT_TIMESTAMP, 0, FALSE,
                            CURRENT_TIMESTAMP, ?, ?)
                    """, secondWorkspace, firstActor);
            return null;
        })).isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(failure.getCause())
                        .hasMessageContaining("row-level security"));
    }

    private <T> T inRuntimeTransaction(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.scope', true)", String.class))
                        .isEqualTo(context.channel().name());
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.workspace_id', true)", String.class))
                        .isEqualTo(context.workspaceId().toString());
                jdbcTemplate.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private void seedAccount(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label + " user");
        jdbcTemplate.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'HOUSEHOLD', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, label + " workspace", actorId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
    }

    private long insertItem(UUID workspaceId, UUID actorId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO item (
                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, CURRENT_TIMESTAMP, 0, FALSE, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """, Long.class, name, workspaceId, actorId);
    }

    private void insertLineMessage(UUID workspaceId, UUID actorId, String content) {
        jdbcTemplate.update("""
                INSERT INTO line_message_log (
                    direction, message_type, content, created_at, pinned, expires_at,
                    workspace_id, created_by_user_id)
                VALUES ('IN', 'TEXT', ?, CURRENT_TIMESTAMP, FALSE,
                        CURRENT_TIMESTAMP + INTERVAL '1 day', ?, ?)
                """, content, workspaceId, actorId);
    }
}

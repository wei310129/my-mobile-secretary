package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationFocusRlsIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_rls_test_runtime";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void grantRuntimeRole() {
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
    }

    @Test
    void focusRowsAreInvisibleAcrossActorAndWorkspaceUnderRuntimeRole() {
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        seed(firstActor, firstWorkspace, "first");
        seed(secondActor, secondWorkspace, "second");
        insertFocus(firstActor, firstWorkspace, "a".repeat(64), "first focus");
        insertFocus(secondActor, secondWorkspace, "b".repeat(64), "second focus");

        assertThat(runtime(new WorkspaceContext(firstActor, firstWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList("SELECT safe_label FROM conversation_focus", String.class)))
                .containsExactly("first focus");
        assertThat(runtime(new WorkspaceContext(secondActor, secondWorkspace, WorkspaceChannel.TEST),
                () -> jdbcTemplate.queryForList("SELECT safe_label FROM conversation_focus", String.class)))
                .containsExactly("second focus");
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbcTemplate.update("INSERT INTO app_user (id, display_name, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", actorId, label);
        jdbcTemplate.update("INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at) VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", workspaceId, label, actorId);
    }

    private void insertFocus(UUID actorId, UUID workspaceId, String digest, String label) {
        jdbcTemplate.update("INSERT INTO conversation_focus (id, channel, conversation_scope_digest, scope_key_version, root_kind, root_domain, workflow_id, safe_label, status, created_at, updated_at, workspace_id, created_by_user_id) VALUES (?, 'TEST', ?, 1, 'WORKFLOW', 'PROJECT', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)", UUID.randomUUID(), digest, UUID.randomUUID(), label, workspaceId, actorId);
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }
}

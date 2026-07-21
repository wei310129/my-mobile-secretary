package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationContextRlsIntegrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConversationContextService conversationContextService;
    @Autowired private ConversationScopeResolver scopeResolver;

    @Test
    void applicationFilterAndRlsKeepConversationReferentsActorAndScopePrivate() {
        UUID workspace = UUID.randomUUID();
        UUID firstActor = UUID.randomUUID();
        UUID peerActor = UUID.randomUUID();
        seedAccount(firstActor, workspace, "first");
        seedUser(peerActor, "peer");
        WorkspaceContext firstRoom = new WorkspaceContext(firstActor, workspace,
                WorkspaceChannel.LINE, "line", "room:first");
        WorkspaceContext secondRoom = new WorkspaceContext(firstActor, workspace,
                WorkspaceChannel.LINE, "line", "room:second");
        WorkspaceContext peerRoom = new WorkspaceContext(peerActor, workspace,
                WorkspaceChannel.LINE, "line", "room:first");
        insertContext(firstRoom, 11L);
        insertContext(secondRoom, 12L);
        insertContext(peerRoom, 21L);

        assertThat(inScope(firstRoom)).isEqualTo(11L);
        assertThat(inScope(secondRoom)).isEqualTo(12L);
        assertThat(inScope(peerRoom)).isEqualTo(21L);
    }

    private Long inScope(WorkspaceContext context) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return conversationContextService.snapshot().lastTaskId();
        }
    }

    private void insertContext(WorkspaceContext context, long taskId) {
        jdbcTemplate.update("""
                INSERT INTO conversation_context (
                    last_task_id, updated_at, channel, conversation_scope_digest, scope_key_version,
                    workspace_id, created_by_user_id)
                VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)
                """, taskId, context.channel().name(), scopeResolver.current(context).digest(),
                scopeResolver.current(context).keyVersion(), context.workspaceId(), context.actorId());
    }

    private void seedAccount(UUID actor, UUID workspace, String label) {
        seedUser(actor, label);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspace, label + " workspace", actor);
    }

    private void seedUser(UUID actor, String label) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actor, label + " user");
    }
}

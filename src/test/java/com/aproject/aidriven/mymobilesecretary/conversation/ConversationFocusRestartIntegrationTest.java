package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationAsyncWorkService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusRestartIntegrationTest extends IntegrationTestBase {

    @Autowired private ConversationAsyncWorkService asyncWorkService;
    @Autowired private ConversationFocusService focusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistedJobAndFocusRemainConsistentAcrossNewRequestContextAndFailureDoesNotRestoreOldFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        UUID jobId;

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.TEST)) {
            jobId = asyncWorkService.start("TRAVEL_REPLAN", UUID.randomUUID(), "巴黎旅行",
                    "a".repeat(64)).jobId();
            focusService.switchWorkflow("PROJECT", UUID.randomUUID(), "目前的東京旅行", "b".repeat(64));
        }

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.BACKGROUND)) {
            assertThat(asyncWorkService.fail(jobId, "暫時無法完成分析")).isTrue();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM conversation_async_work WHERE id = ?",
                String.class, jobId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT safe_label FROM conversation_focus "
                + "WHERE workspace_id = ? AND status = 'ACTIVE'", String.class, workspaceId))
                .isEqualTo("目前的東京旅行");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE workspace_id = ?",
                Long.class, workspaceId)).isEqualTo(1);
    }

    private WorkspaceContextHolder.Scope open(UUID actorId, UUID workspaceId, WorkspaceChannel channel) {
        return WorkspaceContextHolder.open(new WorkspaceContext(actorId, workspaceId, channel));
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Async restart user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Async restart workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusPendingIsolationTest extends IntegrationTestBase {

    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationContextService contextService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void switchHidesOldDestructivePendingUntilTheOriginalFocusResumes() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID firstWorkflowId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST))) {
            UUID firstFocusId = focusService.enterWorkflow(
                    "PROJECT", firstWorkflowId, "甲旅行", "a".repeat(64)).getId();
            contextService.prepareObjectAnnotationDelete(91L);
            assertThat(jdbcTemplate.queryForObject("SELECT conversation_focus_id FROM conversation_context "
                    + "WHERE workspace_id = ?", UUID.class, workspaceId)).isEqualTo(firstFocusId);

            UUID secondFocusId = focusService.switchWorkflow(
                    "PROJECT", UUID.randomUUID(), "乙旅行", "b".repeat(64)).getId();
            assertThat(jdbcTemplate.queryForObject("SELECT id FROM conversation_focus WHERE workspace_id = ? "
                    + "AND status = 'ACTIVE'", UUID.class, workspaceId)).isEqualTo(secondFocusId);
            assertThat(contextService.pendingObjectAnnotationDeleteId()).isNull();

            focusService.exit("c".repeat(64));
            focusService.resume(firstFocusId, "d".repeat(64));
            assertThat(contextService.pendingObjectAnnotationDeleteId()).isEqualTo(91L);
        }
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Pending isolation user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Pending isolation workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

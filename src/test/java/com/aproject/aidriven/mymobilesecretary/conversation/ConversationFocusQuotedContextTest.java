package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusQuotedContextTest extends IntegrationTestBase {

    @Autowired private ConversationFocusQuoteResolver quoteResolver;
    @Autowired private ConversationFocusService focusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void quotedSuspendedFocusResolvesToFixedResumeOnlyInItsOriginalScope() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId, "quoted focus user");
        UUID focusId;

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId)) {
            focusId = focusService.enterWorkflow("TRAVEL", UUID.randomUUID(), "葡萄牙旅行",
                    "a".repeat(64)).getId();
            focusService.exit("b".repeat(64));

            assertThat(quoteResolver.resolveSuspendedFocus(focusId).decision().type())
                    .isEqualTo(FocusTransitionType.RESUME);
            assertThat(quoteResolver.resolveSuspendedFocus(focusId).control().focusId())
                    .isEqualTo(focusId);
        }

        UUID otherActor = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seedAccount(otherActor, otherWorkspace, "other quoted focus user");
        try (WorkspaceContextHolder.Scope ignored = open(otherActor, otherWorkspace)) {
            assertThatThrownBy(() -> quoteResolver.resolveSuspendedFocus(focusId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("quoted focus is unavailable");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                Long.class, otherWorkspace)).isZero();

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId)) {
            focusService.resume(focusId, "c".repeat(64));
            focusService.close(ConversationFocusCloseReason.USER_CLOSED, "d".repeat(64));
            long beforeResolution = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM focus_transition WHERE workspace_id = ?", Long.class, workspaceId);

            assertThatThrownBy(() -> quoteResolver.resolveSuspendedFocus(focusId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("quoted focus is unavailable");

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                    Long.class, workspaceId)).isEqualTo(beforeResolution);
        }
    }

    private WorkspaceContextHolder.Scope open(UUID actorId, UUID workspaceId) {
        return WorkspaceContextHolder.open(new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST));
    }

    private void seedAccount(UUID actorId, UUID workspaceId, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, displayName);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Quoted focus workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

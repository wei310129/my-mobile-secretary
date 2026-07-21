package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusAtomicExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusControl;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusDecision;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusResponseEnvelope;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusTransitionNotice;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusAtomicityIntegrationTest extends IntegrationTestBase {

    @Autowired private ConversationFocusAtomicExecutor executor;
    @Autowired private ConversationFocusService focusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void focusFailureRollsBackTheDomainWriteInTheSameTransaction() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST))) {
            assertThatThrownBy(() -> executor.execute(
                    FocusDecision.transition(FocusTransitionType.EXIT), FocusControl.close(),
                    "a".repeat(64), FocusTransitionNotice.forTransition(
                            FocusTransitionType.EXIT, null, "目前旅行", null), () -> {
                        jdbcTemplate.update("""
                                INSERT INTO item (
                                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                                    workspace_id, created_by_user_id)
                                VALUES ('atomic rollback item', CURRENT_TIMESTAMP, 0, FALSE,
                                    CURRENT_TIMESTAMP, ?, ?)
                                """, workspaceId, actorId);
                        return FocusResponseEnvelope.withoutNotice("主要回覆");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item WHERE name = 'atomic rollback item'", Long.class))
                .isZero();
    }

    @Test
    void rejectedFocusWriteRollsBackDomainWriteAndDoesNotCreateFocusHead() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST))) {
            assertThatThrownBy(() -> executor.execute(
                    FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("PROJECT", UUID.randomUUID(), " "),
                    "b".repeat(64), FocusTransitionNotice.forTransition(
                            FocusTransitionType.ENTER, null, "東京旅行", null), () -> {
                        jdbcTemplate.update("""
                                INSERT INTO item (
                                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                                    workspace_id, created_by_user_id)
                                VALUES ('focus write rollback item', CURRENT_TIMESTAMP, 0, FALSE,
                                    CURRENT_TIMESTAMP, ?, ?)
                                """, workspaceId, actorId);
                        return FocusResponseEnvelope.withoutNotice("主要回覆");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe label");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item WHERE name = 'focus write rollback item'", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM conversation_focus "
                + "WHERE workspace_id = ?", Long.class, workspaceId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM conversation_focus_head "
                + "WHERE workspace_id = ?", Long.class, workspaceId)).isZero();
    }

    @Test
    void failedDomainMutationKeepsTheOriginalFocusAndRollsBackItsOwnWrite() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID originalWorkflowId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST))) {
            focusService.enterWorkflow("PROJECT", originalWorkflowId, "目前旅行", "c".repeat(64));

            assertThatThrownBy(() -> executor.execute(
                    FocusDecision.transition(FocusTransitionType.SWITCH),
                    new FocusControl.SwitchWorkflow("PROJECT", UUID.randomUUID(), "下一趟旅行"),
                    "d".repeat(64), FocusTransitionNotice.forTransition(
                            FocusTransitionType.SWITCH, "目前旅行", "下一趟旅行", null), () -> {
                        jdbcTemplate.update("""
                                INSERT INTO item (
                                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                                    workspace_id, created_by_user_id)
                                VALUES ('domain switch rollback item', CURRENT_TIMESTAMP, 0, FALSE,
                                    CURRENT_TIMESTAMP, ?, ?)
                                """, workspaceId, actorId);
                        throw new IllegalStateException("domain failed");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("domain failed");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item WHERE name = 'domain switch rollback item'", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT workflow_id FROM conversation_focus "
                + "WHERE workspace_id = ? AND status = 'ACTIVE'", UUID.class, workspaceId))
                .isEqualTo(originalWorkflowId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM focus_transition "
                + "WHERE workspace_id = ? AND type = 'SWITCH'", Long.class, workspaceId))
                .isZero();
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Atomic focus user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Atomic focus workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusIdempotencyTest extends IntegrationTestBase {

    @Autowired private ConversationFocusAtomicExecutor transactionalExecutor;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void focusWriteFailureDoesNotReturnAReplyThatClaimsTransitionSucceeded() {
        ConversationFocusService focusService = mock(ConversationFocusService.class);
        ConversationFocusAtomicExecutor executor = new ConversationFocusAtomicExecutor(focusService,
                mock(ConversationFocusReplyDecorator.class));

        assertThatThrownBy(() -> executor.execute(FocusDecision.transition(FocusTransitionType.ENTER),
                new FocusControl.EnterWorkflow("PROJECT", UUID.randomUUID(), "大阪旅行"),
                "b".repeat(64), FocusTransitionNotice.forTransition(
                        FocusTransitionType.ENTER, null, "大阪旅行", null),
                () -> FocusResponseEnvelope.withoutNotice("主要回覆")))
                .isInstanceOf(IllegalStateException.class);

        verify(focusService).enterWorkflow(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateWebhookReplaysOneTerminalReplyWithoutAnotherDomainOrFocusMutation() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String eventKey = "focus-event-" + UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        FocusResponseEnvelope first = processWebhook(actorId, workspaceId, eventKey);
        FocusResponseEnvelope duplicate = processWebhook(actorId, workspaceId, eventKey);

        assertThat(duplicate).isNotNull();
        assertThat(first.notice().type()).isEqualTo(FocusTransitionType.ENTER);
        assertThat(first.message()).contains("主要回覆");
        assertThat(duplicate.message()).isEqualTo(first.message());
        assertThat(duplicate.message().split("目前先處理", -1)).hasSize(2);
        assertThat(duplicate.notice()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM item "
                + "WHERE name = 'idempotent focus item'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM conversation_focus "
                + "WHERE workspace_id = ?", Long.class, workspaceId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM focus_transition "
                + "WHERE workspace_id = ? AND type = 'ENTER'", Long.class, workspaceId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM conversation_focus_head "
                + "WHERE workspace_id = ?", Long.class, workspaceId)).isEqualTo(1L);
    }

    private FocusResponseEnvelope processWebhook(UUID actorId, UUID workspaceId, String eventKey) {
        IdempotencyService.BeginResult reservation = idempotencyService.begin(
                workspaceId, actorId, "LINE", eventKey, "trusted event payload");
        if (reservation.state() == IdempotencyService.State.REPLAY_AVAILABLE) {
            return FocusResponseEnvelope.withoutNotice(reservation.responseBody());
        }
        if (reservation.state() == IdempotencyService.State.COMPLETED_NO_REPLAY) {
            return null;
        }
        assertThat(reservation.state()).isEqualTo(IdempotencyService.State.NEW);
        idempotencyService.markExecutionStarted(workspaceId, actorId, "LINE", eventKey);

        FocusResponseEnvelope reply;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.LINE,
                        "line", "focus-idempotency"))) {
            reply = transactionalExecutor.execute(FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("PROJECT", UUID.randomUUID(), "大阪旅行"),
                    "d".repeat(64), FocusTransitionNotice.forTransition(
                            FocusTransitionType.ENTER, null, "大阪旅行", null), () -> {
                        jdbcTemplate.update("""
                                INSERT INTO item (
                                    name, created_at, inventory_quantity, shopping_needed, updated_at,
                                    workspace_id, created_by_user_id)
                                VALUES ('idempotent focus item', CURRENT_TIMESTAMP, 0, FALSE,
                                    CURRENT_TIMESTAMP, ?, ?)
                                """, workspaceId, actorId);
                        return FocusResponseEnvelope.withoutNotice("主要回覆");
                    });
        }
        idempotencyService.complete(workspaceId, actorId, "LINE", eventKey,
                "FOCUS_ENTERED", reply.message());
        return reply;
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Focus idempotency user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Focus idempotency workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

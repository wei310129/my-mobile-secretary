package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationAsyncWorkService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusOutboxTest extends IntegrationTestBase {

    @Autowired private ConversationAsyncWorkService asyncWorkService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void terminalAsyncDeliveryIsIdempotentAndProducesExactlyOneOutboxEnvelope() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        UUID jobId;

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.TEST)) {
            jobId = asyncWorkService.start("TRAVEL_REPLAN", UUID.randomUUID(), "冰島旅行",
                    "a".repeat(64)).jobId();
        }
        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.BACKGROUND)) {
            assertThat(asyncWorkService.complete(jobId, "分析完成")).isTrue();
            assertThat(asyncWorkService.complete(jobId, "重播不可再送")).isFalse();
        }

        UUID otherActorId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Other async actor', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, otherActorId);
        try (WorkspaceContextHolder.Scope ignored = open(otherActorId, workspaceId, WorkspaceChannel.BACKGROUND)) {
            assertThat(asyncWorkService.complete(jobId, "不得越權重送")).isFalse();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE workspace_id = ?",
                Long.class, workspaceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM conversation_async_work WHERE id = ?",
                String.class, jobId)).isEqualTo("SUCCEEDED");
    }

    private WorkspaceContextHolder.Scope open(UUID actorId, UUID workspaceId, WorkspaceChannel channel) {
        return WorkspaceContextHolder.open(new WorkspaceContext(actorId, workspaceId, channel));
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Async outbox user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Async outbox workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

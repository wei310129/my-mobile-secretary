package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationAsyncWorkService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusAsyncResultTest extends IntegrationTestBase {

    @Autowired private ConversationAsyncWorkService asyncWorkService;
    @Autowired private ConversationFocusService focusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void durableAsyncWorkEntersOnlyAfterItsJobIsSavedAndTerminalResultDoesNotStealNewFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        UUID jobId;

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.TEST)) {
            var started = asyncWorkService.start("TRAVEL_REPLAN", UUID.randomUUID(), "大阪行程",
                    "a".repeat(64));
            jobId = started.jobId();

            assertThat(started.reply().notice().type()).isEqualTo(FocusTransitionType.ENTER);
            assertThat(started.reply().message()).contains("大阪行程");
            assertThat(count("conversation_async_work", workspaceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT root_kind FROM conversation_focus "
                    + "WHERE workspace_id = ? AND status = 'ACTIVE'", String.class, workspaceId))
                    .isEqualTo("ASYNC_WORK");

            focusService.switchWorkflow("PROJECT", UUID.randomUUID(), "目前處理的韓國旅行",
                    "b".repeat(64));
        }

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.BACKGROUND)) {
            assertThat(asyncWorkService.complete(jobId, "已整理出新的行程建議")).isTrue();
        }

        assertThat(status(jobId)).isEqualTo("SUCCEEDED");
        assertThat(activeLabel(workspaceId)).isEqualTo("目前處理的韓國旅行");
        assertThat(count("notification_outbox", workspaceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT message FROM notification_outbox WHERE workspace_id = ?",
                String.class, workspaceId)).contains("大阪行程").doesNotContain(jobId.toString());
    }

    @Test
    void rejectedAsyncEnterRollsBackItsDurableJobWhenAnotherFocusIsAlreadyActive() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId, WorkspaceChannel.TEST)) {
            focusService.enterWorkflow("PROJECT", UUID.randomUUID(), "既有旅行", "c".repeat(64));

            assertThatThrownBy(() -> asyncWorkService.start("TRAVEL_REPLAN", UUID.randomUUID(),
                    "不可進入的背景工作", "d".repeat(64)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active focus");

            assertThat(count("conversation_async_work", workspaceId)).isZero();
            assertThat(activeLabel(workspaceId)).isEqualTo("既有旅行");
        }
    }

    private WorkspaceContextHolder.Scope open(UUID actorId, UUID workspaceId, WorkspaceChannel channel) {
        return WorkspaceContextHolder.open(new WorkspaceContext(actorId, workspaceId, channel));
    }

    private long count(String table, UUID workspaceId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private String status(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM conversation_async_work WHERE id = ?",
                String.class, jobId);
    }

    private String activeLabel(UUID workspaceId) {
        return jdbcTemplate.queryForObject("SELECT safe_label FROM conversation_focus "
                + "WHERE workspace_id = ? AND status = 'ACTIVE'", String.class, workspaceId);
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Async result user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Async result workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

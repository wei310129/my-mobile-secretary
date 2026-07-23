package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationIntentReplayIntegrationTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void duplicateTaskInboundReplaysTheTerminalOutcomeWithoutAnotherMutation() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String text = "提醒我整理保固資料";
        IntentCommand command = command(IntentCommand.Type.CREATE_TASK, "整理保固資料",
                null, null, text);
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            IntentResult first = handle(requestId, text, command);
            IntentResult replay = handle(requestId, text, command);

            assertThat(first.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
            assertThat(replay.action()).isEqualTo(first.action());
            assertThat(replay.responseEnvelope().message())
                    .isEqualTo(first.responseEnvelope().message());
            assertThat(count("task", workspaceId)).isEqualTo(1L);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TASK");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT response_encrypted IS NOT NULL
                    FROM idempotency_record
                    WHERE workspace_id = ? AND actor_user_id = ? AND channel = 'INTENT_TEST'
                    """, Boolean.class, workspaceId, actorId)).isTrue();
        }
    }

    @Test
    void duplicateScheduleInboundReplaysWithoutCreatingAnAmbiguousSecondTarget() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String createText = "下個月二十四號下午兩點到三點排牙醫回診";
        IntentCommand create = command(IntentCommand.Type.CREATE_SCHEDULE, "牙醫回診",
                "2026-08-24T14:00:00+08:00", "2026-08-24T15:00:00+08:00", createText);
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            IntentResult first = handle(requestId, createText, create);
            IntentResult replay = handle(requestId, createText, create);

            assertThat(first.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(replay.action()).isEqualTo(first.action());
            assertThat(replay.responseEnvelope().message())
                    .isEqualTo(first.responseEnvelope().message());
            assertThat(count("schedule_item", workspaceId)).isEqualTo(1L);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("SCHEDULE");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);

            String cancelText = "取消牙醫回診";
            IntentResult canceled = handle(UUID.randomUUID(), cancelText,
                    command(IntentCommand.Type.CANCEL_SCHEDULE, "牙醫回診",
                            null, null, cancelText));

            assertThat(canceled.action()).isEqualTo(IntentResult.Action.SCHEDULE_CANCELED);
            assertThat(count("schedule_item", workspaceId)).isEqualTo(1L);
        }
    }

    @Test
    void reusedRequestIdWithDifferentContentFailsClosedWithoutAnotherMutation() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String firstText = "提醒我整理租約";
            IntentResult first = handle(requestId, firstText,
                    command(IntentCommand.Type.CREATE_TASK, "整理租約",
                            null, null, firstText));
            String conflictingText = "提醒我繳停車費";
            IntentResult conflict = handle(requestId, conflictingText,
                    command(IntentCommand.Type.CREATE_TASK, "繳停車費",
                            null, null, conflictingText));

            assertThat(first.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
            assertThat(conflict.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(conflict.responseEnvelope().message())
                    .contains("不一致", "沒有建立或修改資料")
                    .doesNotContain(actorId.toString(), workspaceId.toString(), requestId.toString());
            assertThat(count("task", workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
        }
    }

    @Test
    void threeArgumentEntryAlsoReplaysAndRejectsPayloadConflict() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String text = "記得提醒我校對保單地址";
            IntentCommand firstCommand = command(
                    IntentCommand.Type.CREATE_TASK, "校對保單地址", null, null, text);
            IntentResult first = handleWithBoundary(requestId, text, firstCommand);
            IntentResult duplicate = handleWithBoundary(requestId, text, firstCommand);
            String conflictText = "記得提醒我換濾水器";
            IntentResult conflict = handleWithBoundary(requestId, conflictText,
                    command(IntentCommand.Type.CREATE_TASK, "換濾水器",
                            null, null, conflictText));

            assertThat(duplicate.action()).isEqualTo(first.action());
            assertThat(duplicate.responseEnvelope().message())
                    .isEqualTo(first.responseEnvelope().message());
            assertThat(conflict.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(conflict.responseEnvelope().message()).contains("不一致", "沒有建立");
            assertThat(count("task", workspaceId)).isEqualTo(1L);
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
        }
    }

    @Test
    void sameRequestIdRemainsIsolatedAcrossActorsWorkspacesAndConversationScopes() {
        UUID firstActor = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        seedAccount(firstActor, firstWorkspace);
        seedAccount(secondActor, secondWorkspace);

        IntentResult first;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(firstActor, firstWorkspace, "first-scope"))) {
            String text = "提醒我更新門禁名單";
            first = handle(requestId, text, command(IntentCommand.Type.CREATE_TASK,
                    "更新門禁名單", null, null, text));
        }
        IntentResult secondScope;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(firstActor, firstWorkspace, "second-scope"))) {
            String text = "提醒我核對會議室設備";
            secondScope = handle(requestId, text, command(IntentCommand.Type.CREATE_TASK,
                    "核對會議室設備", null, null, text));
        }
        IntentResult secondActorResult;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(secondActor, secondWorkspace, "first-scope"))) {
            String text = "提醒我更新緊急聯絡人";
            secondActorResult = handle(requestId, text, command(IntentCommand.Type.CREATE_TASK,
                    "更新緊急聯絡人", null, null, text));
        }

        assertThat(first.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
        assertThat(secondScope.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
        assertThat(secondActorResult.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(firstActor, firstWorkspace, "first-scope"))) {
            assertThat(count("task", firstWorkspace)).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM idempotency_record
                    WHERE workspace_id = ? AND actor_user_id = ? AND idempotency_key LIKE ?
                    """, Long.class, firstWorkspace, firstActor, "%:" + requestId)).isEqualTo(2L);
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(secondActor, secondWorkspace, "first-scope"))) {
            assertThat(count("task", secondWorkspace)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM idempotency_record
                    WHERE workspace_id = ? AND actor_user_id = ? AND idempotency_key LIKE ?
                    """, Long.class, secondWorkspace, secondActor, "%:" + requestId)).isEqualTo(1L);
        }
    }

    private IntentResult handle(UUID requestId, String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(requestId, () -> intentService.handle(text, "TEST"));
    }

    private IntentResult handleWithBoundary(
            UUID requestId, String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(requestId,
                () -> intentService.handle(text, "TEST", () -> { }));
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return context(actorId, workspaceId, "conversation-intent-replay");
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId, String scopeToken) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", scopeToken);
    }

    private static IntentCommand command(IntentCommand.Type type, String title,
                                         String startAt, String endAt, String sourceText) {
        return new IntentCommand(type, title, null, startAt, endAt, null, null, null,
                null, null, null, null, false, null, sourceText);
    }

    private long count(String table, UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private String activeFocusRoot(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT root_domain FROM conversation_focus
                WHERE workspace_id = ? AND status = 'ACTIVE'
                """, String.class, workspaceId);
    }

    private long focusRevision(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT revision FROM conversation_focus_head WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private long transitionCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Conversation replay user', 'ACTIVE', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Conversation replay workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}

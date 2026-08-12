package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationPendingQuestionService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationReply;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationPendingQuestionActualEntryTest extends IntegrationTestBase {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String TEXT = "週五十點開週會，若放假就改週四，颱風停班就順延到下個上班日";

    @Autowired private IntentService intentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationPendingQuestionService pendingQuestions;

    @Test
    void routeQuestionWithoutDraftNeverInheritsAnUnrelatedTaskFocus() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "route-owner-fence");
        UUID taskWorkflow = UUID.fromString("20000000-0000-0000-0000-000000000001");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            focusService.enterWorkflow(
                    "TASK", taskWorkflow, "倒垃圾", "a".repeat(64));
            var pending = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "route.time", "這趟預計幾點出發？"),
                    "b".repeat(64));

            assertThat(pending.getRootDomain()).isEqualTo("route");
            assertThat(pending.getWorkflowId()).isNotEqualTo(taskWorkflow);
            assertThat(pending.getWorkflowSafeLabel()).isNull();
        }
    }

    @Test
    void actualIntentEntryStoresOnlyTypedQuestionAndReplayDoesNotAdvanceRevision() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "pending-question");
        UUID firstInbound = UUID.fromString("10000000-0000-0000-0000-000000000001");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            IntentResult first = RequestCorrelationContext.run(firstInbound,
                    () -> intentService.handle(TEXT, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(firstInbound,
                    () -> intentService.handle(TEXT, "TEST"));

            assertThat(first.nextQuestion().code())
                    .isEqualTo("conditional-recurrence.recurrence");
            assertThat(replay.message()).isEqualTo(first.message());
            assertThat(pendingCount()).isEqualTo(1);
            assertThat(pendingRevision()).isEqualTo(1);

            RequestCorrelationContext.run(
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    () -> intentService.handle(TEXT, "TEST"));

            assertThat(pendingCount()).isEqualTo(1);
            assertThat(pendingRevision()).isEqualTo(2);
            assertThat(pendingQuestionCode())
                    .isEqualTo("conditional-recurrence.recurrence");
        }

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'conversation_pending_question'
                  AND (data_type IN ('json', 'jsonb')
                    OR column_name IN ('prompt', 'message', 'raw_text', 'payload', 'slots'))
                """, String.class)).isEmpty();
    }

    @Test
    void genericUnknownQuestionCannotOverwriteACapabilityOwnedPendingQuestion() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST,
                "integration", "pending-question-unknown-fence");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            var original = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "route.direct-overlap",
                            "請回覆新的出發時間，或回覆照原安排保留。"),
                    "c".repeat(64));

            var retained = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "intent.unknown-action",
                            "你要我執行哪一個具體動作？"),
                    "d".repeat(64));

            assertThat(retained.getId()).isEqualTo(original.getId());
            assertThat(retained.getQuestionCode()).isEqualTo("route.direct-overlap");
            assertThat(retained.getRevision()).isEqualTo(original.getRevision());
            assertThat(pendingQuestionCode()).isEqualTo("route.direct-overlap");
        }
    }

    private long pendingCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private long pendingRevision() {
        return jdbcTemplate.queryForObject("""
                SELECT revision FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private String pendingQuestionCode() {
        return jdbcTemplate.queryForObject("""
                SELECT question_code FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, String.class, WORKSPACE_ID, ACTOR_ID);
    }
}

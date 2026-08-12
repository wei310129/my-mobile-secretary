package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationInboundIdempotency;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationPendingQuestionService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationReply;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationRepairDraftActualEntryTest extends IntegrationTestBase {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private IntentService intentService;
    @Autowired private ConversationPendingQuestionService pendingQuestions;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void feedbackRepairSurvivesAsTypedWorkflowAndAnswerCreatesNoBusinessData() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "repair-draft");
        long beforeTasks = taskCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            IntentResult feedback = RequestCorrelationContext.run(
                    UUID.fromString("20000000-0000-0000-0000-000000000001"),
                    () -> intentService.handle("你沒有聽懂", "TEST"));

            assertThat(feedback.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
            assertThat(feedback.nextQuestion().code()).isEqualTo("conversation-repair.target");
            assertThat(pendingDraftCount()).isEqualTo(1);
            assertThat(pendingWorkflowMatchesDraft()).isTrue();

            IntentResult repaired = RequestCorrelationContext.run(
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    () -> intentService.handle("待辦", "TEST"));

            assertThat(repaired.action()).isEqualTo(IntentResult.Action.TASKS_LISTED);
            assertThat(repaired.message()).contains("沒有建立或修改資料");
            assertThat(completedDraftCount()).isEqualTo(1);
            assertThat(answeredQuestionCount()).isEqualTo(1);
        }

        assertThat(taskCount()).isEqualTo(beforeTasks);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'conversation_repair_draft'
                  AND (data_type IN ('json', 'jsonb') OR column_name IN (
                    'prompt', 'message', 'raw_text', 'payload', 'slots', 'user_text', 'reply'))
                """, String.class)).isEmpty();
    }

    @Test
    void dissatisfactionSuspendsAndRestoresTheExistingBusinessQuestion() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "repair-interjection");
        long beforeTasks = taskCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            UUID businessQuestionId = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "task.title", "您希望這項待辦叫什麼名稱？"),
                    ConversationInboundIdempotency.fromRequestId(
                            UUID.fromString("21000000-0000-0000-0000-000000000001")))
                    .getId();

            IntentResult feedback = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000002"),
                    () -> intentService.handle("這次做得很差", "TEST"));

            assertThat(feedback.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
            assertThat(feedback.nextQuestion()).isNotNull();
            assertThat(statusOfQuestion(businessQuestionId)).isEqualTo("SUSPENDED");
            assertThat(pendingWorkflowMatchesDraft()).isTrue();

            IntentResult repaired = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000003"),
                    () -> intentService.handle("先看待辦", "TEST"));

            assertThat(repaired.action()).isEqualTo(IntentResult.Action.TASKS_LISTED);
            assertThat(repaired.message()).contains("沒有建立或修改資料");
            assertThat(statusOfQuestion(businessQuestionId)).isEqualTo("PENDING");
            assertThat(answeredQuestionCount()).isEqualTo(1);
        }

        assertThat(taskCount()).isEqualTo(beforeTasks);
    }

    @Test
    void dissatisfactionFollowUpPersistsTypedAspectAndKeepsAClarificationActive() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "repair-aspect");
        long beforeTasks = taskCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            RequestCorrelationContext.run(
                    UUID.fromString("22000000-0000-0000-0000-000000000001"),
                    () -> intentService.handle("這次做得很差", "TEST"));

            IntentResult refined = RequestCorrelationContext.run(
                    UUID.fromString("22000000-0000-0000-0000-000000000002"),
                    () -> intentService.handle("回答方式太制式", "TEST"));

            assertThat(refined.nextQuestion().code())
                    .isEqualTo("conversation-repair.presentation-detail");
            assertThat(refined.message())
                    .contains("目前還沒有重新處理")
                    .doesNotContain("我會重新處理", "已經修好");
            assertThat(currentRepairAspect()).isEqualTo("PRESENTATION");
            assertThat(pendingWorkflowMatchesDraft()).isTrue();
        }

        assertThat(taskCount()).isEqualTo(beforeTasks);
    }

    private long taskCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM task", Long.class);
    }

    private long pendingDraftCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_repair_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private long completedDraftCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_repair_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'COMPLETED'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private boolean pendingWorkflowMatchesDraft() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM conversation_pending_question question
                    JOIN conversation_repair_draft draft ON draft.id = question.workflow_id
                    WHERE question.workspace_id = ? AND question.created_by_user_id = ?
                      AND question.status = 'PENDING' AND draft.status = 'PENDING')
                """, Boolean.class, WORKSPACE_ID, ACTOR_ID));
    }

    private long answeredQuestionCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'ANSWERED'
                  AND question_code = 'conversation-repair.target'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private String statusOfQuestion(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM conversation_pending_question WHERE id = ?",
                String.class, id);
    }

    private String currentRepairAspect() {
        return jdbcTemplate.queryForObject("""
                SELECT repair_aspect FROM conversation_repair_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, String.class, WORKSPACE_ID, ACTOR_ID);
    }
}

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
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicReplyEvidence;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationVoicePreferenceActualEntryTest extends IntegrationTestBase {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private IntentService intentService;
    @Autowired private ConversationPendingQuestionService pendingQuestions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void oneTurnPreferencePersistsIsReadableAndIsSemanticallyIdempotent() {
        long tasksBefore = taskCount();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("direct"))) {
            IntentResult saved = handle(
                    "以後你叫做小賈，你要稱呼我為老闆",
                    "22000000-0000-0000-0000-000000000001");

            assertThat(saved.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(saved.nextQuestion()).isNull();
            assertThat(saved.message())
                    .contains("服務名稱已設定為「小賈」", "您的稱呼已設定為「老闆」");
            assertThat(saved.publicReplyEvidence())
                    .contains(PublicReplyEvidence.PREFERENCE_COMMITTED);
            assertThat(setting("assistant_self_name")).isEqualTo("小賈");
            assertThat(setting("user_address")).isEqualTo("老闆");
            long revision = revision();

            IntentResult repeated = handle(
                    "以後你叫做小賈，你要稱呼我為老闆",
                    "22000000-0000-0000-0000-000000000002");
            IntentResult query = handle(
                    "你現在叫什麼名字？",
                    "22000000-0000-0000-0000-000000000003");

            assertThat(repeated.message()).isEqualTo(saved.message());
            assertThat(revision()).isEqualTo(revision);
            assertThat(query.message())
                    .contains("目前的服務名稱是「小賈」", "您的稱呼設定是「老闆」");
            assertThat(taskCount()).isEqualTo(tasksBefore);
            assertThat(forbiddenVoiceStorageColumns()).isEmpty();
        }
    }

    @Test
    void multiTurnPreferenceTemporarilySuspendsAndThenRestoresBusinessQuestion() {
        long tasksBefore = taskCount();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("multi"))) {
            UUID businessQuestionId = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "task.title", "您希望這項待辦叫什麼名稱？"),
                    ConversationInboundIdempotency.fromRequestId(
                            UUID.fromString("22000000-0000-0000-0000-000000000011")))
                    .getId();

            IntentResult first = handle(
                    "可以改稱呼嗎？",
                    "22000000-0000-0000-0000-000000000012");

            assertThat(first.nextQuestion().code()).isEqualTo("voice.target");
            assertThat(questionStatus(businessQuestionId)).isEqualTo("SUSPENDED");
            assertThat(pendingVoiceQuestionCount()).isEqualTo(1);

            IntentResult completed = handle(
                    "以後你叫做小賈，你要稱呼我為老闆",
                    "22000000-0000-0000-0000-000000000013");

            assertThat(completed.nextQuestion()).isNull();
            assertThat(completed.message())
                    .contains("服務名稱已設定為「小賈」", "您的稱呼已設定為「老闆」");
            assertThat(questionStatus(businessQuestionId)).isEqualTo("PENDING");
            assertThat(answeredVoiceQuestionCount()).isEqualTo(1);
            assertThat(completedVoiceDraftCount()).isEqualTo(1);
            assertThat(taskCount()).isEqualTo(tasksBefore);
        }
    }

    @Test
    void praiseRotatesOnlyAnAcknowledgementAndDoesNotInferAStylePreference() {
        long tasksBefore = taskCount();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("praise"))) {
            IntentResult praise = handle(
                    "你做得很好",
                    "22000000-0000-0000-0000-000000000021");

            assertThat(praise.message())
                    .doesNotContain("記住", "保存", "以後都", "我會");
            assertThat(praise.publicReplyEvidence())
                    .containsExactly(PublicReplyEvidence.ACKNOWLEDGED_ONLY);
            assertThat(jdbc.queryForObject("""
                    SELECT assistant_self_name IS NULL
                        AND user_address IS NULL
                        AND response_style IS NULL
                    FROM conversation_voice_profile
                    """, Boolean.class)).isTrue();
            assertThat(taskCount()).isEqualTo(tasksBefore);
        }
    }

    @Test
    void explicitStyleInstructionPersistsTypedPreferenceWithoutConsumingBusinessQuestion() {
        long tasksBefore = taskCount();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("style"))) {
            UUID businessQuestionId = pendingQuestions.record(
                    new PublicConversationReply.NextQuestion(
                            "task.title", "您希望這項待辦叫什麼名稱？"),
                    ConversationInboundIdempotency.fromRequestId(
                            UUID.fromString("22000000-0000-0000-0000-000000000031")))
                    .getId();

            IntentResult saved = handle(
                    "以後都用這種方式回答",
                    "22000000-0000-0000-0000-000000000032");
            IntentResult query = handle(
                    "目前回答風格是什麼？",
                    "22000000-0000-0000-0000-000000000033");

            assertThat(saved.message()).contains("回答偏好已保存", "簡短", "自然", "秘書口吻");
            assertThat(saved.publicReplyEvidence())
                    .contains(PublicReplyEvidence.PREFERENCE_COMMITTED);
            assertThat(setting("response_style")).isEqualTo("CONCISE_WARM_SECRETARY");
            assertThat(query.message()).contains("目前保存的回答偏好", "簡短", "自然", "秘書口吻");
            assertThat(questionStatus(businessQuestionId)).isEqualTo("PENDING");
            assertThat(taskCount()).isEqualTo(tasksBefore);
        }
    }

    private IntentResult handle(String text, String requestId) {
        return RequestCorrelationContext.run(UUID.fromString(requestId),
                () -> intentService.handle(text, "TEST"));
    }

    private WorkspaceContext context(String token) {
        return new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.TEST, "voice-actual-entry", token);
    }

    private long taskCount() {
        return jdbc.queryForObject("SELECT count(*) FROM task", Long.class);
    }

    private String setting(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM conversation_voice_profile",
                String.class);
    }

    private long revision() {
        return jdbc.queryForObject(
                "SELECT revision FROM conversation_voice_profile", Long.class);
    }

    private String questionStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM conversation_pending_question WHERE id = ?",
                String.class, id);
    }

    private long pendingVoiceQuestionCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_pending_question question
                JOIN conversation_voice_preference_draft draft ON draft.id = question.workflow_id
                WHERE question.status = 'PENDING' AND draft.status = 'PENDING'
                """, Long.class);
    }

    private long answeredVoiceQuestionCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE question_code LIKE 'voice.%' AND status = 'ANSWERED'
                """, Long.class);
    }

    private long completedVoiceDraftCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_voice_preference_draft
                WHERE status = 'COMPLETED'
                """, Long.class);
    }

    private java.util.List<String> forbiddenVoiceStorageColumns() {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'conversation_voice_profile', 'conversation_voice_preference_draft')
                  AND (data_type IN ('json', 'jsonb') OR column_name IN (
                    'prompt', 'message', 'raw_text', 'payload', 'slots', 'user_text', 'reply'))
                """, String.class);
    }
}

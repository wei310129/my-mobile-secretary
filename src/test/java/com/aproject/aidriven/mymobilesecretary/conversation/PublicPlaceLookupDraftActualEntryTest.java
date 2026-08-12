package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicPlaceLookupDraftActualEntryTest extends IntegrationTestBase {

    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private IntentService intents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IntentDecisionTraceRepository traces;

    @Test
    void crossRegionAnswerResumesTypedDraftWithoutCreatingAUserPlace() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "system-place");
        long beforePlaces = placeCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            IntentResult ambiguous = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000001"),
                    () -> intents.handle("市政府站在哪裡", "TEST"));

            assertThat(ambiguous.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(ambiguous.nextQuestion().code()).isEqualTo("place.system-region");
            assertThat(pendingDraftCount()).isEqualTo(1);
            assertThat(pendingWorkflowMatchesDraft()).isTrue();

            IntentResult resolved = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000002"),
                    () -> intents.handle("台北市", "TEST"));

            assertThat(resolved.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
            assertThat(resolved.message()).contains("系統公共地點", "沒有建立或修改");
            assertThat(completedDraftCount()).isEqualTo(1);
            assertThat(answeredQuestionCount()).isEqualTo(1);
        }

        assertThat(placeCount()).isEqualTo(beforePlaces);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'public_place_lookup_draft'
                  AND (data_type IN ('json', 'jsonb') OR column_name IN (
                    'prompt', 'message', 'raw_text', 'payload', 'slots', 'user_text', 'reply'))
                """, String.class)).isEmpty();
    }

    @Test
    void systemPlaceCorrectionRepairsInSameTurnWithoutModelOrBusinessMutation() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "place-repair");
        long beforePlaces = placeCount();
        long beforePlans = jdbc.queryForObject(
                "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                Long.class, WORKSPACE_ID);
        UUID requestId = UUID.fromString("21000000-0000-0000-0000-000000000003");

        IntentResult repaired;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            repaired = RequestCorrelationContext.run(requestId, () -> intents.handle(
                    "我是說系統要有自己的捷運站、港口、機場、高鐵、火車站、縣市政府和遊樂園地點",
                    "TEST"));
        }

        assertThat(repaired.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(repaired.message())
                .contains("系統自有公共地點", "不是要建立使用者自訂地點");
        assertThat(placeCount()).isEqualTo(beforePlaces);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                Long.class, WORKSPACE_ID)).isEqualTo(beforePlans);
        assertThat(traces.findByRequestId(requestId)).hasValueSatisfying(trace -> {
            assertThat(trace.getSelectedCapability()).isEqualTo("ASK_PLACE");
            assertThat(trace.getModel()).isNull();
            assertThat(trace.getInputTokens()).isNull();
            assertThat(trace.getOutputTokens()).isNull();
        });
    }

    @Test
    void multipointKnowledgeTurnKeepsTypedCandidatesForAListFollowUp() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "place-points");
        long beforePlaces = placeCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            IntentResult known = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000004"),
                    () -> intents.handle("你知道台北車站嗎？", "TEST"));
            IntentResult listed = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000005"),
                    () -> intents.handle("有哪些點位？", "TEST"));

            assertThat(known.message()).contains("台北火車站", "2 個系統點位");
            assertThat(activeBrowseMode()).isEqualTo("READ_ONLY_MULTIPOINT");
            assertThat(pendingQuestionCount()).isZero();
            assertThat(listed.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
            assertThat(listed.message()).contains("台北捷運", "桃園捷運");
            assertThat(listed.message()).doesNotContain("哪裡", "安排完整行程");
        }

        assertThat(placeCount()).isEqualTo(beforePlaces);
    }

    @Test
    void multipointBrowseFiltersKeywordAndLetsANewPlaceOverrideTheContext() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "place-filter");
        long beforePlaces = placeCount();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000006"),
                    () -> intents.handle("你知道台北車站嗎？", "TEST"));
            IntentResult filtered = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000007"),
                    () -> intents.handle("機場捷運呢？", "TEST"));
            IntentResult allMetro = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000009"),
                    () -> intents.handle("捷運呢？", "TEST"));
            IntentResult missing = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000010"),
                    () -> intents.handle("關鍵字紅線", "TEST"));
            IntentResult newPlace = RequestCorrelationContext.run(
                    UUID.fromString("21000000-0000-0000-0000-000000000008"),
                    () -> intents.handle("你知道捷運大坪林站嗎？", "TEST"));

            assertThat(filtered.message()).contains("找到 1 個符合的系統點位", "桃園捷運")
                    .doesNotContain("台北捷運");
            assertThat(allMetro.message()).contains("找到 2 個符合的系統點位", "台北捷運", "桃園捷運");
            assertThat(missing.message()).contains("沒有符合的結果", "換一個")
                    .doesNotContain("UNKNOWN", "handler", "安排完整行程");
            assertThat(newPlace.message()).isEqualTo(
                    "我知道，您說的是「台北捷運」位於新北市的「捷運大坪林站」。");
        }

        assertThat(placeCount()).isEqualTo(beforePlaces);
    }

    private long placeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM place", Long.class);
    }

    private long pendingDraftCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public_place_lookup_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private String activeBrowseMode() {
        return jdbc.queryForObject("""
                SELECT mode FROM public_place_lookup_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, String.class, WORKSPACE_ID, ACTOR_ID);
    }

    private long pendingQuestionCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'PENDING'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private long completedDraftCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public_place_lookup_draft
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'COMPLETED'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }

    private boolean pendingWorkflowMatchesDraft() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM conversation_pending_question question
                    JOIN public_place_lookup_draft draft ON draft.id = question.workflow_id
                    WHERE question.workspace_id = ? AND question.created_by_user_id = ?
                      AND question.status = 'PENDING' AND draft.status = 'PENDING')
                """, Boolean.class, WORKSPACE_ID, ACTOR_ID));
    }

    private long answeredQuestionCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE workspace_id = ? AND created_by_user_id = ? AND status = 'ANSWERED'
                  AND question_code = 'place.system-region'
                """, Long.class, WORKSPACE_ID, ACTOR_ID);
    }
}

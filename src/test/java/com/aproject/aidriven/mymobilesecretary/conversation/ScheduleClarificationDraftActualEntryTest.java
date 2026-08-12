package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationInboundIdempotency;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationPendingQuestionService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ScheduleClarificationRoutingService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.TrustedConversationReferenceContext;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConditionalRecurrenceConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConditionalVenueConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationSnapshot;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.MonthlyOrdinalRecurrenceConversationService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ScheduleClarificationDraftActualEntryTest extends IntegrationTestBase {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private ConditionalRecurrenceConversationService recurrence;
    @Autowired private ConditionalVenueConversationService venue;
    @Autowired private MonthlyOrdinalRecurrenceConversationService monthly;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConversationPendingQuestionService pendingQuestions;
    @Autowired private ScheduleClarificationRoutingService routing;

    @Test
    void recurrencePersistsKnownSlotsAndCompletesAfterRestartStyleFollowUp() {
        AtomicInteger boundaries = new AtomicInteger();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("recurrence"))) {
            IntentResult first = recurrence.answer(
                    "每週三七點上英文課做到年底，國定假日不用上，補課時間老師會另外說",
                    ConversationSnapshot.empty(), boundaries::incrementAndGet).orElseThrow();

            assertThat(first.nextQuestion().code()).isEqualTo("conditional-recurrence.time-period");
            pendingQuestions.record(first.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            assertThat(value("weekday", "CONDITIONAL_RECURRENCE")).isEqualTo("WEDNESDAY");
            assertThat(value("holiday_policy", "CONDITIONAL_RECURRENCE")).isEqualTo("SKIP");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM conversation_pending_question question
                    JOIN schedule_clarification_draft draft
                      ON draft.id = question.workflow_id
                     AND draft.capability = 'CONDITIONAL_RECURRENCE'
                    WHERE question.status = 'PENDING'
                    """, Long.class)).isEqualTo(1L);
            long revision = number("revision", "CONDITIONAL_RECURRENCE");

            assertThat(recurrence.answer("今天有哪些行程", ConversationSnapshot.empty(),
                    boundaries::incrementAndGet)).isEmpty();
            assertThat(number("revision", "CONDITIONAL_RECURRENCE")).isEqualTo(revision);

            IntentResult completed = recurrence.answer("是晚上七點，每次一小時",
                    ConversationSnapshot.empty(), boundaries::incrementAndGet).orElseThrow();
            assertThat(completed.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
            assertThat(status("CONDITIONAL_RECURRENCE")).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM conversation_pending_question
                    WHERE workflow_id = (SELECT id FROM schedule_clarification_draft
                                         WHERE capability = 'CONDITIONAL_RECURRENCE')
                    """, String.class)).isEqualTo("ANSWERED");
        }
    }

    @Test
    void monthlyAbsorbsDurationWithoutReaskingKnownRuleOrTitle() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("monthly"))) {
            IntentResult first = monthly.answer("下個月第一個星期一早上九點開月會，之後每個月都一樣",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            assertThat(first.nextQuestion().code()).isEqualTo("monthly-ordinal.duration");
            assertThat(number("ordinal_value", "MONTHLY_ORDINAL")).isEqualTo(1);
            assertThat(number("month_offset", "MONTHLY_ORDINAL")).isEqualTo(1);

            IntentResult completed = monthly.answer("每次一小時", ConversationSnapshot.empty(), () -> {})
                    .orElseThrow();
            assertThat(completed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(status("MONTHLY_ORDINAL")).isEqualTo("COMPLETED");
        }
    }

    @Test
    void venueStoresOnlyTypedColumnsAndReadOnlyInterjectionDoesNotAdvanceIt() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("venue"))) {
            IntentResult first = venue.answer(
                    "如果甲館休館就改去乙館活動，我只要一個行程，場地之後決定",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            assertThat(first.nextQuestion().code()).isEqualTo("conditional-venue.event-at");
            long revision = number("revision", "CONDITIONAL_VENUE");

            assertThat(venue.answer("乙館的停車場怎麼走？", ConversationSnapshot.empty(), () -> {}))
                    .isEmpty();
            assertThat(number("revision", "CONDITIONAL_VENUE")).isEqualTo(revision);
            assertThat(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'schedule_clarification_draft'
                      AND (data_type IN ('json', 'jsonb')
                        OR column_name IN ('payload', 'raw_text', 'message', 'prompt', 'slots'))
                    """, String.class)).isEmpty();
        }
    }

    @Test
    void explicitSecondDraftMovesPointerAndAmbiguousAnswerCannotMutateEarlierDraft() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("parallel"))) {
            IntentResult first = recurrence.answer(
                    "每週三晚上七點上課，遇國定假日跳過，做到年底",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(first.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            long recurrenceRevision = number("revision", "CONDITIONAL_RECURRENCE");

            IntentResult second = venue.answer(
                    "如果甲館休館就改去乙館活動，我只要一個行程，場地之後決定",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(second.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM conversation_pending_question question
                    JOIN schedule_clarification_draft draft ON draft.id = question.workflow_id
                    WHERE question.status = 'PENDING'
                      AND draft.capability = 'CONDITIONAL_VENUE'
                    """, Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM conversation_pending_question question
                    JOIN schedule_clarification_draft draft ON draft.id = question.workflow_id
                    WHERE question.status = 'EXPIRED'
                      AND draft.capability = 'CONDITIONAL_RECURRENCE'
                    """, Long.class)).isEqualTo(1L);

            assertThat(recurrence.answer("一小時", ConversationSnapshot.empty(), () -> {}))
                    .isEmpty();
            assertThat(number("revision", "CONDITIONAL_RECURRENCE"))
                    .isEqualTo(recurrenceRevision);
        }
    }

    @Test
    void trustedQuoteTargetsOlderDraftAheadOfActivePointer() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("quote"))) {
            IntentResult first = recurrence.answer(
                    "每週三晚上七點上課，遇國定假日跳過，做到年底",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(first.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            UUID recurrenceId = jdbc.queryForObject("""
                    SELECT id FROM schedule_clarification_draft
                    WHERE capability = 'CONDITIONAL_RECURRENCE'
                    """, UUID.class);

            IntentResult second = venue.answer(
                    "如果甲館休館就改去乙館活動，我只要一個行程，場地之後決定",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(second.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            long recurrenceRevision = number("revision", "CONDITIONAL_RECURRENCE");

            IntentResult quoted;
            try (TrustedConversationReferenceContext.Scope reference =
                    TrustedConversationReferenceContext.openDraft(recurrenceId)) {
                quoted = recurrence.answer("一小時", ConversationSnapshot.empty(), () -> {})
                        .orElseThrow();
            }

            assertThat(quoted.nextQuestion()).isNull();
            assertThat(status("CONDITIONAL_RECURRENCE")).isEqualTo("COMPLETED");
            assertThat(number("revision", "CONDITIONAL_RECURRENCE"))
                    .isGreaterThan(recurrenceRevision);
            assertThat(jdbc.queryForObject("""
                    SELECT draft.capability FROM conversation_pending_question question
                    JOIN schedule_clarification_draft draft ON draft.id = question.workflow_id
                    WHERE question.status = 'PENDING'
                    """, String.class)).isEqualTo("CONDITIONAL_VENUE");
        }
    }

    @Test
    void multipleDraftsWithoutActivePointerAskOneSelectionThenResumeExactWorkflow() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("selection"))) {
            IntentResult recurrenceQuestion = recurrence.answer(
                    "每週三晚上七點上課，遇國定假日跳過，做到年底",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(recurrenceQuestion.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            UUID recurrenceId = jdbc.queryForObject("""
                    SELECT id FROM schedule_clarification_draft
                    WHERE capability = 'CONDITIONAL_RECURRENCE'
                    """, UUID.class);
            IntentResult venueQuestion = venue.answer(
                    "如果甲館休館就改去乙館活動，我只要一個行程，場地之後決定",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(venueQuestion.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            UUID venueId = jdbc.queryForObject("""
                    SELECT id FROM schedule_clarification_draft
                    WHERE capability = 'CONDITIONAL_VENUE'
                    """, UUID.class);
            jdbc.update("UPDATE conversation_pending_question SET status = 'ANSWERED' "
                    + "WHERE status = 'PENDING' AND workflow_id = ?", venueId);

            IntentResult selection = routing.askSelectionIfAmbiguous("一小時").orElseThrow();
            assertThat(selection.nextQuestion().code()).isEqualTo("schedule-draft-selection.target");
            assertThat(selection.message()).contains("條件式週期", "條件場地");
            pendingQuestions.record(selection.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));

            IntentResult resumed = routing.answerSelection("條件式週期").orElseThrow();
            assertThat(resumed.nextQuestion().code()).isEqualTo("conditional-recurrence.duration");
            pendingQuestions.record(resumed.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            assertThat(jdbc.queryForObject("""
                    SELECT workflow_id FROM conversation_pending_question WHERE status = 'PENDING'
                    """, UUID.class)).isEqualTo(recurrenceId);
        }
    }

    @Test
    void cancelAffectsOnlyActiveTypedDraftAndCreatesNoScheduleMutation() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context("cancel"))) {
            IntentResult first = recurrence.answer(
                    "每週三晚上七點上課，遇國定假日跳過，做到年底",
                    ConversationSnapshot.empty(), () -> {}).orElseThrow();
            pendingQuestions.record(first.nextQuestion(),
                    ConversationInboundIdempotency.fromRequestId(UUID.randomUUID()));
            long schedulesBefore = jdbc.queryForObject("SELECT count(*) FROM schedule_item", Long.class);

            IntentResult canceled = routing.cancelSelected("取消這個草稿").orElseThrow();

            assertThat(canceled.message()).contains("沒有建立或修改行程");
            assertThat(status("CONDITIONAL_RECURRENCE")).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM conversation_pending_question
                    WHERE workflow_id = (SELECT id FROM schedule_clarification_draft
                                         WHERE capability = 'CONDITIONAL_RECURRENCE')
                    """, String.class)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_item", Long.class))
                    .isEqualTo(schedulesBefore);
        }
    }

    private WorkspaceContext context(String token) {
        return new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST, "typed-draft", token);
    }

    private String value(String column, String capability) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM schedule_clarification_draft "
                + "WHERE capability = ?", String.class, capability);
    }

    private long number(String column, String capability) {
        return jdbc.queryForObject("SELECT " + column + " FROM schedule_clarification_draft "
                + "WHERE capability = ?", Long.class, capability);
    }

    private String status(String capability) {
        return value("status", capability);
    }
}

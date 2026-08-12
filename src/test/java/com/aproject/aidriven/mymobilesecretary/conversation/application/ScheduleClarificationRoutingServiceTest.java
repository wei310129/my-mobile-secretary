package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ScheduleClarificationDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleClarificationRoutingServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    private static final ConversationScopeKey SCOPE = new ConversationScopeKey("a".repeat(64), 1);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private ConversationScopeResolver resolver;
    private ConversationPendingQuestionService pending;
    private ScheduleClarificationDraftRepository drafts;
    private ScheduleClarificationRoutingService service;

    @BeforeEach
    void setUp() {
        resolver = mock(ConversationScopeResolver.class);
        pending = mock(ConversationPendingQuestionService.class);
        drafts = mock(ScheduleClarificationDraftRepository.class);
        when(resolver.current(org.mockito.ArgumentMatchers.any())).thenReturn(SCOPE);
        service = new ScheduleClarificationRoutingService(resolver, pending, drafts,
                Clock.fixed(NOW, ZoneOffset.UTC));
        WorkspaceContextHolder.open(new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.TEST, "routing", "scope"));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void ambiguousSlotAnswerAsksExactlyOneWorkflowSelection() {
        ScheduleClarificationDraft recurrence = recurrence();
        ScheduleClarificationDraft venue = venue();
        when(pending.current()).thenReturn(Optional.empty());
        stubDrafts(List.of(recurrence, venue));

        var result = service.askSelectionIfAmbiguous("一小時").orElseThrow();

        assertThat(result.nextQuestion().code()).isEqualTo("schedule-draft-selection.target");
        assertThat(result.message()).contains("條件式週期", "條件場地");
        assertThat(result.message().chars().filter(character -> character == '？').count())
                .isEqualTo(1);
    }

    @Test
    void selectedWorkflowReturnsItsActualNextMissingQuestion() {
        ScheduleClarificationDraft recurrence = recurrence();
        ScheduleClarificationDraft venue = venue();
        ConversationPendingQuestion selection = ConversationPendingQuestion.pending(
                SCOPE, WorkspaceChannel.TEST, null, "schedule_draft_selection",
                UUID.randomUUID(), "schedule-draft-selection.target", "b".repeat(64),
                NOW.plusSeconds(600), NOW);
        when(pending.current()).thenReturn(Optional.of(selection));
        stubDrafts(List.of(recurrence, venue));

        var result = service.answerSelection("第一個").orElseThrow();

        assertThat(result.nextQuestion().code()).isEqualTo("conditional-recurrence.duration");
        assertThat(result.message()).doesNotContain(venue.getId().toString());
    }

    @Test
    void questionInterjectionDoesNotConsumeSelection() {
        ConversationPendingQuestion selection = ConversationPendingQuestion.pending(
                SCOPE, WorkspaceChannel.TEST, null, "schedule_draft_selection",
                UUID.randomUUID(), "schedule-draft-selection.target", "b".repeat(64),
                NOW.plusSeconds(600), NOW);
        when(pending.current()).thenReturn(Optional.of(selection));
        stubDrafts(List.of(recurrence(), venue()));

        assertThat(service.answerSelection("今天有哪些行程？")).isEmpty();
        assertThat(selection.getRevision()).isEqualTo(1);
        assertThat(selection.getStatus()).isEqualTo(ConversationPendingQuestionStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "幫我新增一個任務", "修改明天行程", "取消明天行程", "設定提醒",
            "查詢今天行程", "列出未完成任務", "完成目前任務", "購買清單上的項目"
    })
    void explicitNewRequestIsNotCapturedBySelection(String text) {
        when(pending.current()).thenReturn(Optional.empty());
        stubDrafts(List.of(recurrence(), venue()));

        assertThat(service.askSelectionIfAmbiguous(text)).isEmpty();
    }

    @Test
    void cancelClosesOnlyPointedDraftAndPendingQuestion() {
        ScheduleClarificationDraft recurrence = recurrence();
        ScheduleClarificationDraft venue = venue();
        ConversationPendingQuestion question = ConversationPendingQuestion.pending(
                SCOPE, WorkspaceChannel.TEST, null, "conditional_recurrence",
                recurrence.getId(), "conditional-recurrence.duration", "b".repeat(64),
                NOW.plusSeconds(600), NOW);
        when(pending.current()).thenReturn(Optional.of(question));
        stubDrafts(List.of(recurrence, venue));

        var result = service.cancelSelected("取消這個草稿").orElseThrow();

        assertThat(result.message()).contains("沒有建立或修改行程");
        assertThat(recurrence.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.CANCELED);
        assertThat(question.getStatus()).isEqualTo(ConversationPendingQuestionStatus.CANCELED);
        assertThat(venue.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.PENDING);
    }

    @Test
    void cancelingSelectionPreservesEveryDomainDraft() {
        ScheduleClarificationDraft recurrence = recurrence();
        ScheduleClarificationDraft venue = venue();
        ConversationPendingQuestion selection = ConversationPendingQuestion.pending(
                SCOPE, WorkspaceChannel.TEST, null, "schedule_draft_selection",
                UUID.randomUUID(), "schedule-draft-selection.target", "b".repeat(64),
                NOW.plusSeconds(600), NOW);
        when(pending.current()).thenReturn(Optional.of(selection));
        stubDrafts(List.of(recurrence, venue));

        var result = service.cancelSelected("取消這個草稿").orElseThrow();

        assertThat(result.message()).contains("原有草稿都保留", "沒有建立或修改行程");
        assertThat(selection.getStatus()).isEqualTo(ConversationPendingQuestionStatus.CANCELED);
        assertThat(recurrence.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.PENDING);
        assertThat(venue.getStatus()).isEqualTo(ScheduleClarificationDraftStatus.PENDING);
    }

    private ScheduleClarificationDraft recurrence() {
        ScheduleClarificationDraft draft = ScheduleClarificationDraft.create(
                SCOPE, WorkspaceChannel.TEST, ScheduleClarificationCapability.CONDITIONAL_RECURRENCE,
                NOW.plusSeconds(600), NOW);
        draft.mergeRecurrence("課程", java.time.DayOfWeek.WEDNESDAY,
                java.time.LocalTime.of(19, 0), true, null,
                java.time.LocalDate.of(2026, 12, 31), true, "SKIP", null, null, NOW);
        return draft;
    }

    private ScheduleClarificationDraft venue() {
        return ScheduleClarificationDraft.create(
                SCOPE, WorkspaceChannel.TEST, ScheduleClarificationCapability.CONDITIONAL_VENUE,
                NOW.plusSeconds(600), NOW);
    }

    private void stubDrafts(List<ScheduleClarificationDraft> values) {
        when(drafts
                .findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusOrderByCreatedAtAsc(
                        WORKSPACE, ACTOR, WorkspaceChannel.TEST, SCOPE.digest(),
                        ScheduleClarificationDraftStatus.PENDING)).thenReturn(values);
    }
}

package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationRepairDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationVoiceProfileService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairAspect;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FeedbackPolarity;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationRepairServiceTest {

    private ConversationContextService context;
    private IntentHandlerRegistry handlers;
    private ConversationRepairService service;

    @BeforeEach
    void setUp() {
        context = mock(ConversationContextService.class);
        handlers = mock(IntentHandlerRegistry.class);
        service = new ConversationRepairService(context, handlers);
    }

    @Test
    void crossDomainFeedbackImmediatelyRerunsAReadOnlyUnifiedAgenda() {
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                null, null, null, List.of(), List.of(), "TASKS_LISTED",
                "今天有哪些待辦", "先前的待辦清單"));
        when(handlers.dispatch(any(), any())).thenReturn(IntentResult.message(
                IntentResult.Action.AGENDA_LISTED, "今天的待辦與行程已重新整理"));

        IntentResult result = service.answer(
                "你沒有把待辦和行程的時間關聯一起看，才會回答錯重點").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.message()).contains("漏了兩者的時間關聯", "已重新整理");
        assertThat(result.nextQuestion()).isNull();
        verify(context, never()).preserveReferencesForInterjection();
    }

    @Test
    void feedbackWithoutEnoughContextAsksOneTypedRepairQuestion() {
        when(context.snapshot()).thenReturn(ConversationSnapshot.empty());

        IntentResult result = service.answer("你沒有聽懂").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code()).isEqualTo("conversation-repair.target");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void independentMutationDoesNotConsumeRepairDraft() {
        ConversationRepairDraftService drafts = mock(ConversationRepairDraftService.class);
        ConversationRepairDraft draft = mock(ConversationRepairDraft.class);
        when(drafts.current()).thenReturn(Optional.of(draft));
        service.setDrafts(drafts);

        assertThat(service.answer("取消第二個待辦")).isEmpty();

        verify(drafts, never()).complete(any());
        verify(handlers, never()).dispatch(any(), any());
    }

    @Test
    void praiseUsesDurableRotationAndDoesNotRepeatTheSameReply() {
        ConversationVoiceProfileService profiles = mock(ConversationVoiceProfileService.class);
        when(profiles.nextFeedbackVariant(FeedbackPolarity.PRAISE, 10))
                .thenReturn(new ConversationVoiceProfileService.Variant(
                        0, ConversationVoiceProfileService.Settings.defaults()))
                .thenReturn(new ConversationVoiceProfileService.Variant(
                        1, ConversationVoiceProfileService.Settings.defaults()));
        service.setVoiceProfiles(profiles);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult first = service.answer("你做得很好", mutations::incrementAndGet).orElseThrow();
        IntentResult second = service.answer("你做得很好", mutations::incrementAndGet).orElseThrow();

        assertThat(first.message()).isNotEqualTo(second.message());
        assertThat(first.nextQuestion()).isNull();
        assertThat(second.nextQuestion()).isNull();
        assertThat(mutations).hasValue(2);
    }

    @Test
    void dissatisfactionUsesOneActiveQuestionWithoutPromisingARerun() {
        ConversationVoiceProfileService profiles = mock(ConversationVoiceProfileService.class);
        ConversationRepairDraftService drafts = mock(ConversationRepairDraftService.class);
        when(profiles.nextFeedbackVariant(FeedbackPolarity.DISSATISFACTION, 10))
                .thenReturn(new ConversationVoiceProfileService.Variant(
                        0, ConversationVoiceProfileService.Settings.defaults()));
        when(context.snapshot()).thenReturn(ConversationSnapshot.empty());
        service.setVoiceProfiles(profiles);
        service.setDrafts(drafts);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service.answer("你做得很差", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.message()).contains("您最希望我先釐清哪一點")
                .doesNotContain("我會重新處理", "我會依", "我會調整");
        assertThat(mutations.get()).isGreaterThanOrEqualTo(1);
        verify(drafts).start(ConversationRepairType.DISSATISFACTION, ConversationSnapshot.empty());
    }

    @Test
    void answerAboutReplyStyleIsConsumedByTheTypedRepairInsteadOfFallingToUnknown() {
        ConversationRepairDraftService drafts = mock(ConversationRepairDraftService.class);
        ConversationRepairDraft draft = mock(ConversationRepairDraft.class);
        when(drafts.current()).thenReturn(Optional.of(draft));
        service.setDrafts(drafts);

        IntentResult result = service.answer("回答方式太制式").orElseThrow();

        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code())
                .isEqualTo("conversation-repair.presentation-detail");
        assertThat(result.message())
                .contains("回答方式")
                .doesNotContain("已重新處理", "我會重新處理", "已經修好");
        verify(handlers, never()).dispatch(any(), any());
        verify(drafts, never()).complete(any());
        verify(drafts).refine(draft.getId(), ConversationRepairAspect.PRESENTATION);
    }
}

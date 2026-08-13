package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationContextTransitionServiceTest {

    @Test
    void threeCommonStatusPhrasesExposeTheSameTypedContextWithoutConsumingPending() {
        ConversationPendingQuestionService pendingQuestions = mock(ConversationPendingQuestionService.class);
        ConversationOperationLifecycleService lifecycle = mock(ConversationOperationLifecycleService.class);
        ConversationOperationLifecycleContributor.LifecycleContext context =
                new ConversationOperationLifecycleContributor.LifecycleContext("前往車站", "確認出發地",
                        List.of("時間已確認"), List.of("還需要出發地"));
        when(lifecycle.resumeQuestion()).thenReturn(Optional.of(
                new ConversationOperationLifecycleContributor.ResumeQuestion("route.origin", "origin",
                        "請提供出發地", 120, context)));
        ConversationContextTransitionService service =
                new ConversationContextTransitionService(pendingQuestions, lifecycle);

        for (String phrase : List.of("現在處理到哪裡？", "現在在處理哪個項目？", "目前在哪個階段？")) {
            ConversationContextTransitionService.Transition transition =
                    service.intercept(phrase, hmac('a')).orElseThrow();
            assertThat(transition.action()).isEqualTo(ConversationContextTransitionService.Action.STATUS);
            assertThat(transition.lifecycleContext()).isEqualTo(context);
        }
        verifyNoInteractions(pendingQuestions);
    }

    @Test
    void newOperationFromAnOrdinaryPendingQuestionStartsTypedContextChoice() {
        ConversationPendingQuestionService pendingQuestions = mock(ConversationPendingQuestionService.class);
        ConversationOperationLifecycleService lifecycle = mock(ConversationOperationLifecycleService.class);
        ConversationPendingQuestion pending = mock(ConversationPendingQuestion.class);
        when(pending.getQuestionCode()).thenReturn("route.origin");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.beginContextChoice(hmac('b'), null)).thenReturn(Optional.of(pending));
        ConversationContextTransitionService service =
                new ConversationContextTransitionService(pendingQuestions, lifecycle);

        ConversationContextTransitionService.Transition transition =
                service.intercept("開始新的操作", hmac('b')).orElseThrow();

        assertThat(transition.action()).isEqualTo(ConversationContextTransitionService.Action.CHOOSE_CONTEXT);
        verify(pendingQuestions).beginContextChoice(hmac('b'), null);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void continueFromContextChoiceRestoresTheInterruptedTypedQuestion() {
        ConversationPendingQuestionService pendingQuestions = mock(ConversationPendingQuestionService.class);
        ConversationOperationLifecycleService lifecycle = mock(ConversationOperationLifecycleService.class);
        ConversationPendingQuestion pending = mock(ConversationPendingQuestion.class);
        when(pending.getQuestionCode()).thenReturn("conversation.context-target");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        when(pendingQuestions.resumeInterruptedQuestion(hmac('c'))).thenReturn(Optional.of(pending));
        ConversationContextTransitionService service =
                new ConversationContextTransitionService(pendingQuestions, lifecycle);

        ConversationContextTransitionService.Transition transition =
                service.intercept("繼續", hmac('c')).orElseThrow();

        assertThat(transition.action()).isEqualTo(ConversationContextTransitionService.Action.RESUME_INTERRUPTED);
        verify(pendingQuestions).resumeInterruptedQuestion(hmac('c'));
        verifyNoInteractions(lifecycle);
    }

    private static String hmac(char value) {
        return String.valueOf(value).repeat(64);
    }
}

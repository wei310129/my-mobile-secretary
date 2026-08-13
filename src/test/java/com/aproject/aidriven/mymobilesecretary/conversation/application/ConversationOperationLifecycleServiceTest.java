package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationOperationLifecycleServiceTest {

    @Test
    void replayedCloseConsumesPendingFenceBeforeItCanRepeatDomainMutation() {
        UUID workflowId = UUID.randomUUID();
        ConversationFocus focus = mock(ConversationFocus.class);
        when(focus.getRootDomain()).thenReturn("calendar");
        when(focus.getWorkflowId()).thenReturn(workflowId);
        when(focus.getSafeLabel()).thenReturn("前往車站");
        ConversationFocusService focuses = mock(ConversationFocusService.class);
        when(focuses.activeFocus()).thenReturn(Optional.of(focus));
        ConversationPendingQuestionService pendingQuestions = mock(ConversationPendingQuestionService.class);
        when(pendingQuestions.cancelCurrent(workflowId, hmac('a'))).thenReturn(true);
        when(pendingQuestions.cancelCurrent(workflowId, hmac('b'))).thenReturn(false);
        RecordingContributor contributor = new RecordingContributor(workflowId);
        ConversationOperationLifecycleService service = new ConversationOperationLifecycleService(
                new ConversationOperationLifecycleRegistry(List.of(contributor)), pendingQuestions, focuses);

        ConversationOperationLifecycleService.ClearResult first = service.closeCurrent(hmac('a'));
        ConversationOperationLifecycleService.ClearResult replay = service.closeCurrent(hmac('b'));

        assertThat(first.closed()).isTrue();
        assertThat(first.domainMutationCount()).isEqualTo(1);
        assertThat(replay.replayIgnored()).isTrue();
        assertThat(contributor.closeCalls).isEqualTo(1);
        verify(focuses).close(ConversationFocusCloseReason.USER_CLOSED, hmac('a'));
        verify(focuses, times(1)).close(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resumeExposesTypedContextRatherThanARecentUserMessage() {
        UUID workflowId = UUID.randomUUID();
        ConversationFocus focus = mock(ConversationFocus.class);
        when(focus.getRootDomain()).thenReturn("calendar");
        when(focus.getWorkflowId()).thenReturn(workflowId);
        when(focus.getSafeLabel()).thenReturn("前往車站");
        ConversationFocusService focuses = mock(ConversationFocusService.class);
        when(focuses.activeFocus()).thenReturn(Optional.of(focus));
        ConversationPendingQuestionService pendingQuestions = mock(ConversationPendingQuestionService.class);
        com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion pending =
                mock(com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion.class);
        when(pending.getQuestionCode()).thenReturn("route.origin");
        when(pendingQuestions.current()).thenReturn(Optional.of(pending));
        RecordingContributor contributor = new RecordingContributor(workflowId);
        ConversationOperationLifecycleService service = new ConversationOperationLifecycleService(
                new ConversationOperationLifecycleRegistry(List.of(contributor)), pendingQuestions, focuses);

        ConversationOperationLifecycleContributor.ResumeQuestion question = service.resumeQuestion().orElseThrow();

        assertThat(question.lifecycleContext().publicTopic()).isEqualTo("前往車站");
        assertThat(question.lifecycleContext().activeStep()).isEqualTo("確認出發地");
        assertThat(question.lifecycleContext().preservedFacts()).containsExactly("時間已確認");
        assertThat(question.lifecycleContext().unresolvedFacts()).containsExactly("還需要出發地");
    }

    private static String hmac(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class RecordingContributor implements ConversationOperationLifecycleContributor {

        private final UUID workflowId;
        private int closeCalls;

        private RecordingContributor(UUID workflowId) {
            this.workflowId = workflowId;
        }

        @Override
        public String operationKind() {
            return "test-route";
        }

        @Override
        public Optional<Operation> resolve(Reference reference) {
            return workflowId.equals(reference.workflowId())
                    ? Optional.of(new Operation(operationKind(), "calendar", workflowId, null,
                    "前往車站", true)) : Optional.empty();
        }

        @Override
        public Optional<ResumeQuestion> resumeQuestion(Operation operation, String currentQuestionCode) {
            return Optional.of(new ResumeQuestion(currentQuestionCode, "origin", "請提供出發地", 120,
                    new LifecycleContext("前往車站", "確認出發地", List.of("時間已確認"),
                            List.of("還需要出發地"))));
        }

        @Override
        public CloseOutcome close(Operation operation) {
            closeCalls++;
            return new CloseOutcome(1, true);
        }
    }
}

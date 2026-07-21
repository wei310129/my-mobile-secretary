package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationFocusAtomicityTest {

    @Test
    void domainFailureDoesNotWriteFocusOrProduceTerminalEnvelope() {
        ConversationFocusService focusService = mock(ConversationFocusService.class);
        ConversationFocusAtomicExecutor executor = new ConversationFocusAtomicExecutor(focusService,
                mock(ConversationFocusReplyDecorator.class));

        assertThatThrownBy(() -> executor.execute(FocusDecision.transition(FocusTransitionType.ENTER),
                new FocusControl.EnterWorkflow("PROJECT", UUID.randomUUID(), "大阪旅行"),
                "a".repeat(64), FocusTransitionNotice.forTransition(
                        FocusTransitionType.ENTER, null, "大阪旅行", null), () -> {
                    throw new IllegalStateException("domain failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain failed");

        verifyNoInteractions(focusService);
    }

    @Test
    void exitIsDelegatedOnlyAfterTheDomainCallbackReturnsItsReply() {
        ConversationFocusService focusService = mock(ConversationFocusService.class);
        ConversationFocusReplyDecorator decorator = mock(ConversationFocusReplyDecorator.class);
        when(decorator.decorate("主要回覆", FocusTransitionNotice.forTransition(
                FocusTransitionType.EXIT, null, "大阪旅行", null)))
                .thenReturn("主要回覆\n\n已結束目前工作焦點。");
        ConversationFocusAtomicExecutor executor = new ConversationFocusAtomicExecutor(focusService,
                decorator);

        executor.execute(FocusDecision.transition(FocusTransitionType.EXIT), FocusControl.exit(),
                "c".repeat(64), FocusTransitionNotice.forTransition(
                        FocusTransitionType.EXIT, null, "大阪旅行", null),
                () -> FocusResponseEnvelope.withoutNotice("主要回覆"));

        verify(focusService).exit("c".repeat(64));
        verify(decorator).decorate("主要回覆", FocusTransitionNotice.forTransition(
                FocusTransitionType.EXIT, null, "大阪旅行", null));
    }
}

package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import org.junit.jupiter.api.Test;

class ConversationFocusIntentHandlerTest {

    @Test
    void existingIntentEntryIsClassifiedByTheAuthoritativeJavaCatalog() {
        ConversationFocusIntentHandler handler = new ConversationFocusIntentHandler(
                new ConversationFocusCapabilityCatalog(), new ConversationFocusCoordinator(
                        new ConversationFocusTransitionPolicy(), mock(ConversationFocusService.class)));

        assertThat(handler.behaviorFor(IntentCommand.Type.ASK_WEATHER))
                .isEqualTo(FocusBehavior.ONE_SHOT_KEEP);
        assertThat(handler.behaviorFor(IntentCommand.Type.FEEDBACK))
                .isEqualTo(FocusBehavior.NEVER_TOUCH);
    }

    @Test
    void intentEntryDelegatesToTheUniqueJavaFocusDecision() {
        ConversationFocusIntentHandler handler = new ConversationFocusIntentHandler(
                new ConversationFocusCapabilityCatalog(), new ConversationFocusCoordinator(
                        new ConversationFocusTransitionPolicy(), mock(ConversationFocusService.class)));

        assertThat(handler.decide(IntentCommand.Type.CREATE_TASK, FocusControl.none(), true))
                .isEqualTo(FocusDecision.clarify());
    }
}

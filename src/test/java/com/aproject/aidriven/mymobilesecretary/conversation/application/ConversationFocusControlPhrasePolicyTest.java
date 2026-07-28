package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import org.junit.jupiter.api.Test;

class ConversationFocusControlPhrasePolicyTest {

    @Test
    void recognizesExplicitTopicControlsAcrossSpacingAndPunctuation() {
        assertThat(ConversationFocusControlPhrasePolicy.classify(
                "先  離開目前焦點！！過一下再說"))
                .contains(IntentCommand.Type.EXIT_CONVERSATION_FOCUS);
        assertThat(ConversationFocusControlPhrasePolicy.classify(
                "這段對話就結束，不要再接續。"))
                .contains(IntentCommand.Type.CLOSE_CONVERSATION_FOCUS);
    }

    @Test
    void doesNotCaptureBusinessExitOrOrdinaryCompletionLanguage() {
        assertThat(ConversationFocusControlPhrasePolicy.classify("離開家時提醒我倒垃圾"))
                .isEmpty();
        assertThat(ConversationFocusControlPhrasePolicy.classify("會議結束後去接小孩"))
                .isEmpty();
        assertThat(ConversationFocusControlPhrasePolicy.classify("關掉晚上九點的提醒"))
                .isEmpty();
    }
}

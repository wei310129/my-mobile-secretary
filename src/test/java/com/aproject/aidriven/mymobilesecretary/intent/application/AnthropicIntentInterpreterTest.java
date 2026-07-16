package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class AnthropicIntentInterpreterTest {

    @Test
    void readsJsonGenerationAfterLeadingThinkingBlock() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("")),
                new Generation(new AssistantMessage("""
                        {"commands":[{"type":"LIST_TASKS"}]}
                        """))));

        IntentScript script = AnthropicIntentInterpreter.convertStructuredResponse(response);

        assertThat(script.commands()).hasSize(1);
        assertThat(script.commands().getFirst().type()).isEqualTo(IntentCommand.Type.LIST_TASKS);
    }
}

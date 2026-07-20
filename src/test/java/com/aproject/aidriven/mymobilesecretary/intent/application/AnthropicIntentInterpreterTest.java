package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

class AnthropicIntentInterpreterTest {

    @Test
    void legacySystemPromptDoesNotDuplicateFullConversationCatalog() {
        String prompt = AnthropicIntentInterpreter.systemPrompt();
        String outputSchema = new BeanOutputConverter<>(IntentScript.class).getFormat();

        assertThat(prompt).doesNotContain("001|幫我記得買牛奶");
        assertThat(prompt.length()).isLessThan(12_500);
        assertThat(outputSchema.length()).isLessThan(15_000);
        assertThat(prompt.length() + outputSchema.length()).isLessThan(27_000);
    }

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

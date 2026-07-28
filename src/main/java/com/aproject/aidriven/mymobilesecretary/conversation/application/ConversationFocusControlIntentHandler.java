package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic business-neutral replies for explicit focus-only controls. */
@Component
public final class ConversationFocusControlIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED = Set.of(
            IntentCommand.Type.EXIT_CONVERSATION_FOCUS,
            IntentCommand.Type.CLOSE_CONVERSATION_FOCUS);

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case EXIT_CONVERSATION_FOCUS -> IntentResult.message(
                    IntentResult.Action.CONTEXT_UPDATED,
                    "只離開目前的對話焦點；既有資料不會完成、取消或刪除。")
                    .withFocusDirective(ConversationFocusDirective.FOCUS_CONTROL_ONLY);
            case CLOSE_CONVERSATION_FOCUS -> IntentResult.message(
                    IntentResult.Action.CONTEXT_UPDATED,
                    "只結束目前的對話承接；業務資料不會完成、取消或刪除。")
                    .withFocusDirective(ConversationFocusDirective.FOCUS_CONTROL_ONLY);
            default -> throw new IllegalArgumentException(
                    "unsupported conversation focus control " + command.type());
        };
    }
}

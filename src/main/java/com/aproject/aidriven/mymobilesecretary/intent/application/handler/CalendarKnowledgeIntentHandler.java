package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Routes typed Calendar knowledge commands to deterministic Java orchestration. */
@Component
public class CalendarKnowledgeIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> TYPES = Set.of(
            IntentCommand.Type.ASK_CALENDAR_KNOWLEDGE,
            IntentCommand.Type.BIND_KNOWLEDGE_TO_CALENDAR,
            IntentCommand.Type.MATERIALIZE_CALENDAR_KNOWLEDGE);

    private final CalendarKnowledgeConversationService service;

    public CalendarKnowledgeIntentHandler(CalendarKnowledgeConversationService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case ASK_CALENDAR_KNOWLEDGE -> service.ask(command);
            case BIND_KNOWLEDGE_TO_CALENDAR -> service.bind(command);
            case MATERIALIZE_CALENDAR_KNOWLEDGE -> service.materialize(command);
            default -> throw new IllegalArgumentException(
                    "unsupported Calendar knowledge intent " + command.type());
        };
    }
}

package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarCategoryConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Routes typed Calendar category commands to deterministic Java orchestration. */
@Component
public class CalendarCategoryIntentHandler implements IntentHandler {

    private final CalendarCategoryConversationService service;

    public CalendarCategoryIntentHandler(CalendarCategoryConversationService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(IntentCommand.Type.CHANGE_CALENDAR_CATEGORY);
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return service.change(command);
    }
}

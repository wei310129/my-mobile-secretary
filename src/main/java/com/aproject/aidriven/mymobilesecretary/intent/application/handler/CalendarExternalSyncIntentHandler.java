package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarExternalSyncConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Routes external-calendar questions to a deterministic zero-mutation explanation. */
@Component
public class CalendarExternalSyncIntentHandler implements IntentHandler {

    private final CalendarExternalSyncConversationService service;

    public CalendarExternalSyncIntentHandler(
            CalendarExternalSyncConversationService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(
                IntentCommand.Type.EXPLAIN_CALENDAR_EXTERNAL_SYNC);
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return service.explain(command);
    }
}

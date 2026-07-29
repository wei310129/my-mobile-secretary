package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentConversationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Routes typed Calendar attachment commands to deterministic Java orchestration. */
@Component
public class CalendarAttachmentIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> TYPES = Set.of(
            IntentCommand.Type.BIND_CALENDAR_ATTACHMENT_AND_PREVIEW_SHARE,
            IntentCommand.Type.MANAGE_CALENDAR_ATTACHMENT);

    private final CalendarAttachmentConversationService service;

    public CalendarAttachmentIntentHandler(CalendarAttachmentConversationService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case BIND_CALENDAR_ATTACHMENT_AND_PREVIEW_SHARE ->
                service.bindAndPreviewShare(command);
            case MANAGE_CALENDAR_ATTACHMENT -> service.manage(command);
            default -> throw new IllegalArgumentException(
                    "unsupported Calendar attachment intent " + command.type());
        };
    }
}

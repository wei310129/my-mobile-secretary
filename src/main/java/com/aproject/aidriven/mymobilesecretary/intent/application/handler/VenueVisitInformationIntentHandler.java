package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.venue.application.VenueVisitInformationService;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class VenueVisitInformationIntentHandler implements IntentHandler {
    private final VenueVisitInformationService service;

    public VenueVisitInformationIntentHandler(VenueVisitInformationService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(IntentCommand.Type.RECORD_VENUE_VISIT_INFO,
                IntentCommand.Type.ASK_VENUE_VISIT_INFO);
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        if (command.type() == IntentCommand.Type.ASK_VENUE_VISIT_INFO) {
            String keyword = command.placeName() == null ? command.title() : command.placeName();
            return service.query(keyword);
        }
        var options = command.safeOptions();
        boolean reservationRequired = text != null && text.contains("預約");
        return service.record(command.placeName(), command.title(),
                options.description() == null ? text : options.description(),
                reservationRequired, options.quantity());
    }
}

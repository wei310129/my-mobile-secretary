package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Controlled intent-entry bridge; F3A only classifies, and deliberately does not render notices. */
@Component
public final class ConversationFocusIntentHandler {

    private final ConversationFocusCapabilityCatalog catalog;
    private final ConversationFocusCoordinator coordinator;

    public ConversationFocusIntentHandler(ConversationFocusCapabilityCatalog catalog,
                                          ConversationFocusCoordinator coordinator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public FocusBehavior behaviorFor(IntentCommand.Type type) {
        return catalog.behaviorFor(type);
    }

    public FocusDecision decide(IntentCommand.Type type, FocusControl control,
                                boolean hasActiveFocus) {
        return coordinator.coordinate(behaviorFor(type), control, hasActiveFocus);
    }
}

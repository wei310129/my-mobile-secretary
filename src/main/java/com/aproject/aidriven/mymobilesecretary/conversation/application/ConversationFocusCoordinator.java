package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Produces a unique typed decision. Transition persistence is intentionally deferred to F4A. */
@Component
public final class ConversationFocusCoordinator {

    private final ConversationFocusTransitionPolicy policy;
    private final ConversationFocusService focusService;

    public ConversationFocusCoordinator(ConversationFocusTransitionPolicy policy,
                                        ConversationFocusService focusService) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.focusService = Objects.requireNonNull(focusService, "focusService");
    }

    public FocusDecision coordinate(FocusBehavior behavior, FocusControl control,
                                    boolean hasActiveFocus) {
        return policy.decide(behavior, control, hasActiveFocus);
    }
}

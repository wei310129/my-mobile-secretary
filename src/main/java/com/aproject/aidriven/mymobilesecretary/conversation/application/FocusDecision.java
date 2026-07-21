package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.Objects;

/** Exactly one Java outcome for one focus-aware conversation turn. */
public sealed interface FocusDecision permits FocusDecision.Keep, FocusDecision.Clarify,
        FocusDecision.Transition {

    static Keep keep() { return Keep.INSTANCE; }
    static Clarify clarify() { return Clarify.INSTANCE; }
    static Transition transition(FocusTransitionType type) { return new Transition(type); }

    enum Keep implements FocusDecision { INSTANCE }
    enum Clarify implements FocusDecision { INSTANCE }

    record Transition(FocusTransitionType type) implements FocusDecision {
        public Transition {
            Objects.requireNonNull(type, "type");
        }
    }
}

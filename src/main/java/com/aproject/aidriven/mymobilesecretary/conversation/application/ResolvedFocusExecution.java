package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;

/** A domain result together with the Java-only focus decision derived from that persisted result. */
public record ResolvedFocusExecution<T>(
        T response,
        FocusDecision decision,
        FocusControl control,
        FocusTransitionNotice notice) {

    public ResolvedFocusExecution {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(control, "control");
    }

    public static <T> ResolvedFocusExecution<T> keep(T response) {
        return new ResolvedFocusExecution<>(response, FocusDecision.keep(), FocusControl.none(), null);
    }
}

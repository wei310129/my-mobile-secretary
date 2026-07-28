package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import java.util.Objects;
import java.util.UUID;

/** Trusted candidate data retained until an explicit, revision-fenced acceptance. */
public record PendingFocusCandidate(FocusTransitionType transitionType, String rootDomain,
                                    UUID workflowId, String safeLabel) {

    public PendingFocusCandidate {
        if (transitionType != FocusTransitionType.ENTER && transitionType != FocusTransitionType.SWITCH) {
            throw new IllegalArgumentException("pending focus candidate must enter or switch workflow");
        }
        rootDomain = required(rootDomain, "root domain", 60);
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        safeLabel = required(safeLabel, "safe label", 200);
    }

    private static String required(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}

package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Objects;
import java.util.Set;

/** One typed public option. Labels and effects come from a capability catalog, not a renderer. */
public record PublicConversationChoice(
        String actionCode,
        String label,
        String effect,
        Set<String> acceptedAnswers) {

    public PublicConversationChoice {
        if (actionCode == null || actionCode.isBlank()
                || label == null || label.isBlank()
                || effect == null || effect.isBlank()) {
            throw new IllegalArgumentException("choice action, label and effect are required");
        }
        if (label.contains("\n") || effect.contains("\n")) {
            throw new IllegalArgumentException("choice label and effect must each fit one line");
        }
        acceptedAnswers = Set.copyOf(Objects.requireNonNull(acceptedAnswers, "acceptedAnswers"));
        if (acceptedAnswers.isEmpty() || acceptedAnswers.stream().anyMatch(
                answer -> answer == null || answer.isBlank() || answer.contains("\n"))) {
            throw new IllegalArgumentException("at least one single-line accepted answer is required");
        }
    }
}

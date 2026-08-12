package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Comparator;
import java.util.List;

/** One deterministic blocking question owned by a conversation capability. */
public record ClarificationStep(
        String code,
        String slot,
        String prompt,
        int priority,
        boolean blocksMutation) {

    public ClarificationStep {
        if (code == null || code.isBlank() || slot == null || slot.isBlank()
                || prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("clarification code, slot and prompt are required");
        }
        if (prompt.contains("\n")) {
            throw new IllegalArgumentException("clarification prompt must contain one question only");
        }
    }

    public static ClarificationStep blocking(
            String code, String slot, String prompt, int priority) {
        return new ClarificationStep(code, slot, prompt, priority, true);
    }

    public static ClarificationStep first(List<ClarificationStep> unresolved) {
        if (unresolved == null || unresolved.isEmpty()) {
            throw new IllegalArgumentException("at least one unresolved clarification is required");
        }
        return unresolved.stream()
                .min(Comparator.comparingInt(ClarificationStep::priority))
                .orElseThrow();
    }

    public PublicConversationReply.NextQuestion nextQuestion() {
        return new PublicConversationReply.NextQuestion(code, prompt);
    }
}

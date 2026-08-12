package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Objects;
import java.util.Set;

/** Typed carrier for the only text that an adapter may send to a user. */
public record PublicConversationReply(
        String message,
        TerminalState terminalState,
        NextQuestion nextQuestion,
        Set<PublicReplyEvidence> evidence) {

    public PublicConversationReply {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("public reply message is required");
        }
        Objects.requireNonNull(terminalState, "terminalState");
        evidence = evidence == null ? Set.of() : Set.copyOf(evidence);
    }

    public PublicConversationReply(
            String message, TerminalState terminalState, NextQuestion nextQuestion) {
        this(message, terminalState, nextQuestion, Set.of());
    }

    public static PublicConversationReply terminal(String message, TerminalState terminalState) {
        return new PublicConversationReply(message, terminalState, null);
    }

    public static PublicConversationReply terminal(
            String message, TerminalState terminalState, PublicReplyEvidence... evidence) {
        return new PublicConversationReply(message, terminalState, null,
                evidence == null ? Set.of() : Set.of(evidence));
    }

    public enum TerminalState {
        SUCCEEDED,
        NEEDS_INPUT,
        FAILED,
        IN_PROGRESS
    }

    /** Phase B will require this typed value for every needs-input response. */
    public record NextQuestion(String code, String prompt) {
        public NextQuestion {
            if (code == null || code.isBlank() || prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("next question code and prompt are required");
            }
        }
    }
}

package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record KnowledgeMaterializationSource(
        CalendarKnowledgeBindingView.SourceKind sourceKind,
        UUID bindingId,
        Instant sourceUpdatedAt,
        long bindingRevision,
        String channel,
        String conversationScopeKey) {

    public KnowledgeMaterializationSource {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
        if (bindingRevision <= 0) {
            throw new IllegalArgumentException("binding revision must be positive");
        }
        channel = required(channel, 30, "channel").toUpperCase(Locale.ROOT);
        if (!channel.matches("[A-Z][A-Z0-9_]{0,29}")) {
            throw new IllegalArgumentException("channel must be a stable identifier");
        }
        conversationScopeKey =
                required(conversationScopeKey, 240, "conversation scope key");
    }

    private static String required(String value, int maximum, String label) {
        if (value == null
                || value.isBlank()
                || value.strip().length() > maximum) {
            throw new IllegalArgumentException(
                    label + " must be non-blank and at most " + maximum + " characters");
        }
        return value.strip();
    }
}

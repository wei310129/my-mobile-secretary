package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.util.UUID;

public record KnowledgeMaterializationConsent(
        String requestKey,
        UUID proposalId,
        long proposalRevision,
        String channel,
        String conversationScopeKey,
        boolean confirmed) {
}

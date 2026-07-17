package com.aproject.aidriven.mymobilesecretary.account.audit;

import java.util.UUID;

public record SecurityAuditDraft(
        UUID workspaceId,
        UUID actorUserId,
        String eventType,
        String targetType,
        String targetId,
        SecurityAuditEvent.Outcome outcome,
        String reasonCode,
        String channel,
        UUID requestId
) {
}

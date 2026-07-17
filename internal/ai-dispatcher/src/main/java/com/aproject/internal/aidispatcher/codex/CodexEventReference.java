package com.aproject.internal.aidispatcher.codex;

import java.time.Instant;

public record CodexEventReference(
        String sourceKey,
        String sourceEventId,
        String triggerType,
        String subjectRef,
        int schemaVersion,
        Instant occurredAt
) {
}

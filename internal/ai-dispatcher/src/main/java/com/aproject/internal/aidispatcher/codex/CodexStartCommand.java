package com.aproject.internal.aidispatcher.codex;

import java.util.List;
import java.util.UUID;

public record CodexStartCommand(
        UUID runId,
        long fencingToken,
        String sessionKey,
        String sessionDisplayName,
        String externalSessionId,
        List<CodexEventReference> events
) {

    public CodexStartCommand {
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("A Codex start command requires at least one event");
        }
    }
}

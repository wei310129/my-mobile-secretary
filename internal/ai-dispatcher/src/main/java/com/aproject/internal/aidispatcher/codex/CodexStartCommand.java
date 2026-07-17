package com.aproject.internal.aidispatcher.codex;

import java.util.List;
import java.util.UUID;

public record CodexStartCommand(
        UUID runId,
        long fencingToken,
        String sessionKey,
        String sessionDisplayName,
        String sessionProvider,
        String externalSessionId,
        long sessionBindingVersion,
        List<CodexEventReference> events
) {

    public CodexStartCommand {
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("A Codex start command requires at least one event");
        }
        if (sessionKey == null || sessionKey.isBlank()
                || sessionDisplayName == null || sessionDisplayName.isBlank()
                || sessionProvider == null || sessionProvider.isBlank()
                || externalSessionId == null || externalSessionId.isBlank()
                || sessionBindingVersion < 0) {
            throw new IllegalArgumentException("A Codex start command requires a session binding snapshot");
        }
    }
}

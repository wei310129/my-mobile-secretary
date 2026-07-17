package com.aproject.internal.aidispatcher.session;

public record UnbindDevelopmentSessionCommand(
        Long expectedVersion,
        String actorId,
        String reason
) {
}

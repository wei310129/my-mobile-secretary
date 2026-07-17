package com.aproject.internal.aidispatcher.session;

public record BindDevelopmentSessionCommand(
        String externalSessionId,
        Long expectedVersion,
        String actorId,
        String reason
) {
}

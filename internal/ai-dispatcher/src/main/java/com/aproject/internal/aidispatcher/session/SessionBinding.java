package com.aproject.internal.aidispatcher.session;

import java.time.Instant;

public record SessionBinding(
        String sessionKey,
        String displayName,
        String provider,
        String externalSessionId,
        AgentSessionStatus status,
        long version,
        Instant boundAt,
        Instant lastVerifiedAt,
        Instant updatedAt
) {

    public boolean isReady() {
        return status == AgentSessionStatus.READY && externalSessionId != null;
    }
}

package com.aproject.internal.aidispatcher.session.api;

import com.aproject.internal.aidispatcher.session.SessionBinding;
import java.time.Instant;

record SessionBindingResponse(
        String sessionKey,
        String displayName,
        String provider,
        String externalSessionId,
        String status,
        long version,
        Instant boundAt,
        Instant lastVerifiedAt,
        Instant updatedAt
) {

    static SessionBindingResponse from(SessionBinding binding) {
        return new SessionBindingResponse(
                binding.sessionKey(),
                binding.displayName(),
                binding.provider(),
                binding.externalSessionId(),
                binding.status().name(),
                binding.version(),
                binding.boundAt(),
                binding.lastVerifiedAt(),
                binding.updatedAt());
    }
}

package com.aproject.internal.aidispatcher.domain;

import java.time.Instant;
import java.util.Objects;

public record DispatchSchedule(
        Instant quietUntil,
        Instant forceUntil,
        Instant retryNotBefore,
        Instant eligibleAt
) {

    public DispatchSchedule {
        Objects.requireNonNull(quietUntil, "quietUntil");
        Objects.requireNonNull(forceUntil, "forceUntil");
        Objects.requireNonNull(eligibleAt, "eligibleAt");
    }

    public boolean isEligibleAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(eligibleAt);
    }
}

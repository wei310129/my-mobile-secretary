package com.aproject.internal.aidispatcher.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class QuietPeriodPolicy {

    private final Duration quietPeriod;
    private final Duration maximumWait;

    public QuietPeriodPolicy(Duration quietPeriod, Duration maximumWait) {
        this.quietPeriod = requirePositive(quietPeriod, "quietPeriod");
        this.maximumWait = requirePositive(maximumWait, "maximumWait");
        if (maximumWait.compareTo(quietPeriod) < 0) {
            throw new IllegalArgumentException("maximumWait must not be shorter than quietPeriod");
        }
    }

    public DispatchSchedule schedule(PendingEventWindow window,
                                     Instant previousRunFinishedAt,
                                     Instant retryNotBefore) {
        Objects.requireNonNull(window, "window");
        Instant quietAnchor = laterOf(window.lastRecordedAt(), previousRunFinishedAt);
        Instant quietUntil = quietAnchor.plus(quietPeriod);
        Instant forceUntil = window.firstRecordedAt().plus(maximumWait);
        Instant debounceEligibleAt = earlierOf(quietUntil, forceUntil);
        Instant eligibleAt = laterOf(debounceEligibleAt, retryNotBefore);
        return new DispatchSchedule(quietUntil, forceUntil, retryNotBefore, eligibleAt);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Instant earlierOf(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant laterOf(Instant required, Instant optional) {
        if (optional == null || required.isAfter(optional)) {
            return required;
        }
        return optional;
    }
}

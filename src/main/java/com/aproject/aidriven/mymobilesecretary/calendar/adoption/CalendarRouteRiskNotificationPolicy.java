package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CalendarRouteRiskNotificationPolicy {

    private static final Duration DEPARTURE_ADVANCE_THRESHOLD =
            Duration.ofMinutes(10);

    public boolean shouldNotify(
            Snapshot previous,
            long fromNodeRevision,
            long toNodeRevision,
            PersonalRouteStatus riskKind,
            Duration requiredTravel,
            Instant recommendedDeparture) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(riskKind, "riskKind");
        Objects.requireNonNull(requiredTravel, "requiredTravel");
        Objects.requireNonNull(recommendedDeparture, "recommendedDeparture");
        if (!riskKind.equals(PersonalRouteStatus.IMPOSSIBLE)
                && !riskKind.equals(PersonalRouteStatus.ALTERNATIVE_AVAILABLE)) {
            throw new IllegalArgumentException("Only active route risks can notify");
        }
        if (fromNodeRevision < 1
                || toNodeRevision < 1
                || requiredTravel.isZero()
                || requiredTravel.isNegative()) {
            throw new IllegalArgumentException("Route risk evidence is invalid");
        }
        return previous.lifecycle() == Lifecycle.RESOLVED
                || previous.fromNodeRevision() != fromNodeRevision
                || previous.toNodeRevision() != toNodeRevision
                || previous.riskKind() != riskKind
                || requiredTravel.compareTo(previous.lastNotifiedRequiredTravel()) > 0
                || !recommendedDeparture.isAfter(previous.lastNotifiedDeparture()
                        .minus(DEPARTURE_ADVANCE_THRESHOLD));
    }

    public enum Lifecycle {
        OPEN,
        CONFIRMED,
        RESOLVED
    }

    public record Snapshot(
            Lifecycle lifecycle,
            long fromNodeRevision,
            long toNodeRevision,
            PersonalRouteStatus riskKind,
            Duration lastNotifiedRequiredTravel,
            Instant lastNotifiedDeparture) {

        public Snapshot {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(riskKind, "riskKind");
            Objects.requireNonNull(
                    lastNotifiedRequiredTravel,
                    "lastNotifiedRequiredTravel");
            Objects.requireNonNull(lastNotifiedDeparture, "lastNotifiedDeparture");
            if (fromNodeRevision < 1
                    || toNodeRevision < 1
                    || lastNotifiedRequiredTravel.isZero()
                    || lastNotifiedRequiredTravel.isNegative()) {
                throw new IllegalArgumentException("Previous route risk is invalid");
            }
        }
    }
}

package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.util.Objects;

/** Deterministic route classification; raw structured-output end times are never an input. */
public final class RouteJourneyKindPolicy {

    private RouteJourneyKindPolicy() {}

    public static RouteJourneyKind resolve(
            CalendarPlacement placement, SourceSemantics sourceSemantics) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(sourceSemantics, "source semantics");
        return switch (sourceSemantics) {
            case EXPLICIT_STANDALONE_TRIP -> requireTimedPoint(placement);
            case EXPLICIT_ACTIVITY_WITH_TRANSPORT -> requireTimedInterval(placement);
        };
    }

    private static RouteJourneyKind requireTimedPoint(CalendarPlacement placement) {
        if (!(placement instanceof CalendarPlacement.TimedPoint)) {
            throw new IllegalArgumentException(
                    "A standalone route requires one verified departure time");
        }
        return RouteJourneyKind.STANDALONE_TRIP;
    }

    private static RouteJourneyKind requireTimedInterval(CalendarPlacement placement) {
        if (!(placement instanceof CalendarPlacement.TimedInterval)) {
            throw new IllegalArgumentException(
                    "Transport attached to an activity requires a verified activity interval");
        }
        return RouteJourneyKind.ACTIVITY_WITH_TRANSPORT;
    }

    public enum SourceSemantics {
        EXPLICIT_STANDALONE_TRIP,
        EXPLICIT_ACTIVITY_WITH_TRANSPORT
    }
}

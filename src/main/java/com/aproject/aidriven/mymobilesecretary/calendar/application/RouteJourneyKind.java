package com.aproject.aidriven.mymobilesecretary.calendar.application;

/**
 * The verified business meaning of a route request.
 *
 * <p>This is deliberately independent of raw model fields. The route entry path chooses it only
 * after {@code CalendarPlacement} and typed source semantics have been validated.</p>
 */
public enum RouteJourneyKind {
    STANDALONE_TRIP,
    ACTIVITY_WITH_TRANSPORT
}

package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;

/** Deterministic origin decision; free text and model confidence are never persisted here. */
public record RouteOriginContextDecision(Kind kind, CalendarLocation location) {

    public enum Kind {
        NEARBY_PREVIOUS,
        HOME,
        NEED_HOME,
        POSSIBLY_AWAY
    }

    public boolean hasLocation() {
        return location != null;
    }
}

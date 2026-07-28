package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.util.List;

public record PersonalRouteProjection(
        List<CalendarBusyInterval> busyIntervals,
        List<PersonalRouteConstraint> routeConstraints) {

    public PersonalRouteProjection {
        busyIntervals = List.copyOf(busyIntervals);
        routeConstraints = List.copyOf(routeConstraints);
    }
}

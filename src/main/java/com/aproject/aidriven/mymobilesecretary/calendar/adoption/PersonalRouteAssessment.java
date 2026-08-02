package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.time.Duration;

public record PersonalRouteAssessment(
        PersonalRouteStatus status,
        PersonalRouteConstraint from,
        PersonalRouteConstraint to,
        Duration requiredTravel,
        Duration availableGap) {

    public String fromNodeKey() {
        return from == null ? null : from.nodeKey();
    }

    public String toNodeKey() {
        return to == null ? null : to.nodeKey();
    }

    public boolean isRouteRisk() {
        return status == PersonalRouteStatus.IMPOSSIBLE
                || status == PersonalRouteStatus.ALTERNATIVE_AVAILABLE;
    }
}

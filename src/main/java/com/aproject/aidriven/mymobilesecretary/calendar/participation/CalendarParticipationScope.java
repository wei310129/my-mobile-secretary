package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.Objects;
import java.util.UUID;

public record CalendarParticipationScope(
        CalendarParticipationScopeType type, UUID targetId) {

    public CalendarParticipationScope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetId, "targetId");
    }

    public static CalendarParticipationScope plan(UUID planId) {
        return new CalendarParticipationScope(
                CalendarParticipationScopeType.PLAN, planId);
    }

    public static CalendarParticipationScope activity(UUID activityId) {
        return new CalendarParticipationScope(
                CalendarParticipationScopeType.ACTIVITY, activityId);
    }
}

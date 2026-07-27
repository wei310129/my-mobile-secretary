package com.aproject.aidriven.mymobilesecretary.calendar.task;

import java.util.Objects;
import java.util.UUID;

public record CalendarTaskTarget(
        TargetKind kind, UUID planId, UUID activityId, UUID nodeId) {

    public CalendarTaskTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(planId, "planId");
        boolean valid = switch (kind) {
            case PLAN -> activityId == null && nodeId == null;
            case ACTIVITY -> activityId != null && nodeId == null;
            case NODE -> activityId == null && nodeId != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Calendar task target shape is invalid");
        }
    }

    public static CalendarTaskTarget plan(UUID planId) {
        return new CalendarTaskTarget(TargetKind.PLAN, planId, null, null);
    }

    public static CalendarTaskTarget activity(UUID planId, UUID activityId) {
        return new CalendarTaskTarget(TargetKind.ACTIVITY, planId, activityId, null);
    }

    public static CalendarTaskTarget node(UUID planId, UUID nodeId) {
        return new CalendarTaskTarget(TargetKind.NODE, planId, null, nodeId);
    }

    public enum TargetKind {
        PLAN,
        ACTIVITY,
        NODE
    }
}

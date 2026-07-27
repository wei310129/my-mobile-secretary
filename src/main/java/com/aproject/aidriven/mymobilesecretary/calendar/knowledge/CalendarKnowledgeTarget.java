package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.util.Objects;
import java.util.UUID;

public record CalendarKnowledgeTarget(
        TargetKind kind, UUID planId, UUID activityId, UUID nodeId) {

    public CalendarKnowledgeTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(planId, "planId");
        boolean valid = switch (kind) {
            case PLAN -> activityId == null && nodeId == null;
            case ACTIVITY -> activityId != null && nodeId == null;
            case NODE -> activityId == null && nodeId != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Calendar knowledge target shape is invalid");
        }
    }

    public static CalendarKnowledgeTarget plan(UUID planId) {
        return new CalendarKnowledgeTarget(TargetKind.PLAN, planId, null, null);
    }

    public static CalendarKnowledgeTarget activity(UUID planId, UUID activityId) {
        return new CalendarKnowledgeTarget(TargetKind.ACTIVITY, planId, activityId, null);
    }

    public static CalendarKnowledgeTarget node(UUID planId, UUID nodeId) {
        return new CalendarKnowledgeTarget(TargetKind.NODE, planId, null, nodeId);
    }

    public enum TargetKind {
        PLAN,
        ACTIVITY,
        NODE
    }
}

package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import java.util.Objects;
import java.util.UUID;

public record CalendarAttachmentTarget(
        TargetKind kind, UUID planId, UUID activityId, UUID nodeId) {

    public CalendarAttachmentTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(planId, "planId");
        boolean valid = switch (kind) {
            case PLAN -> activityId == null && nodeId == null;
            case ACTIVITY -> activityId != null && nodeId == null;
            case NODE -> activityId == null && nodeId != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Calendar attachment target shape is invalid");
        }
    }

    public static CalendarAttachmentTarget plan(UUID planId) {
        return new CalendarAttachmentTarget(TargetKind.PLAN, planId, null, null);
    }

    public static CalendarAttachmentTarget activity(UUID planId, UUID activityId) {
        return new CalendarAttachmentTarget(TargetKind.ACTIVITY, planId, activityId, null);
    }

    public static CalendarAttachmentTarget node(UUID planId, UUID nodeId) {
        return new CalendarAttachmentTarget(TargetKind.NODE, planId, null, nodeId);
    }

    public enum TargetKind {
        PLAN,
        ACTIVITY,
        NODE
    }
}

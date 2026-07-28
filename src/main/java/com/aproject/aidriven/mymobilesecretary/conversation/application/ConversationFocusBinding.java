package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.UUID;

/** Trusted resource or workflow identity returned by a domain handler after execution. */
public record ConversationFocusBinding(
        String domain,
        String routingKey,
        String safeLabel,
        UUID workflowId,
        String activityCode,
        String activityLabel) {

    public ConversationFocusBinding(String domain, String routingKey, String safeLabel) {
        this(domain, routingKey, safeLabel, null, null, null);
    }

    public ConversationFocusBinding {
        domain = required(domain, "domain", 60);
        safeLabel = required(safeLabel, "safeLabel", 120);
        if ((routingKey == null) == (workflowId == null)) {
            throw new IllegalArgumentException(
                    "focus binding requires exactly one resource or workflow identity");
        }
        routingKey = routingKey == null ? null : required(routingKey, "routingKey", 200);
        if ((activityCode == null) != (activityLabel == null)) {
            throw new IllegalArgumentException(
                    "focus binding activity code and label must be provided together");
        }
        activityCode = activityCode == null ? null : required(activityCode, "activityCode", 80);
        activityLabel = activityLabel == null ? null : required(activityLabel, "activityLabel", 120);
    }

    public static ConversationFocusBinding workflow(
            String domain, UUID workflowId, String safeLabel) {
        return new ConversationFocusBinding(
                domain, null, safeLabel, workflowId, null, null);
    }

    public static ConversationFocusBinding workflowActivity(
            String domain, UUID workflowId, String safeLabel,
            String activityCode, String activityLabel) {
        return new ConversationFocusBinding(
                domain, null, safeLabel, workflowId, activityCode, activityLabel);
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new IllegalArgumentException("focus binding " + field + " is required");
        }
        return value.strip();
    }
}

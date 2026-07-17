package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentReplyFormatter;
import java.util.Objects;
import java.util.UUID;

/** Immutable, tenant-bound delivery envelope passed to a notification channel. */
public record ReminderNotification(
        UUID workspaceId,
        UUID targetUserId,
        UUID deliveryId,
        String destination,
        Long reminderId,
        Long taskId,
        String title,
        String message
) {
    public ReminderNotification {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(targetUserId, "targetUserId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        destination = requireText(destination, "destination", 500);
        title = requireText(title, "title", 200);
        message = IntentReplyFormatter.formatNotification(title, requireText(message, "message", 20_000));
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return stripped;
    }
}

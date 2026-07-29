package com.aproject.aidriven.mymobilesecretary.integration.notification;

import java.util.Set;
import java.util.UUID;

public record NotificationRequest(
        UUID targetUserId,
        String deliveryKey,
        Long reminderId,
        Long taskId,
        String title,
        String message,
        Set<NotificationChannel> allowedChannels
) {
    public NotificationRequest(
            UUID targetUserId,
            String deliveryKey,
            Long reminderId,
            Long taskId,
            String title,
            String message) {
        this(targetUserId, deliveryKey, reminderId, taskId, title, message, Set.of());
    }

    public NotificationRequest {
        deliveryKey = requireText(deliveryKey, "deliveryKey", 250);
        title = requireText(title, "title", 200);
        message = requireText(message, "message", 20_000);
        allowedChannels = Set.copyOf(allowedChannels == null ? Set.of() : allowedChannels);
    }

    public boolean allows(NotificationChannel channel) {
        return allowedChannels.isEmpty() || allowedChannels.contains(channel);
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return stripped;
    }
}

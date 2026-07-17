package com.aproject.aidriven.mymobilesecretary.integration.notification;

import java.util.Optional;
import java.util.UUID;

/** A tenant-aware notification channel adapter. */
public interface NotificationSender {

    NotificationChannel channel();

    /** Resolves an explicit opaque destination; the default deliberately fails closed. */
    default Optional<String> destinationFor(UUID workspaceId, UUID targetUserId) {
        return Optional.empty();
    }

    /** True only when the adapter/provider deduplicates retries by deliveryId. */
    default boolean supportsStableDeliveryId() {
        return false;
    }

    void send(ReminderNotification notification);
}

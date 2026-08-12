package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.account.application.ExternalIdentityService;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sends tenant-bound proactive notifications through the LINE Messaging API. */
@Component
public class LineNotificationSender implements NotificationSender {

    private final ExternalIdentityService identities;
    private final LineMessagingClient client;
    private final LineProperties properties;

    public LineNotificationSender(
            ExternalIdentityService identities,
            LineMessagingClient client,
            LineProperties properties) {
        this.identities = identities;
        this.client = client;
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.LINE;
    }

    @Override
    public Optional<String> destinationFor(UUID workspaceId, UUID targetUserId) {
        if (!properties.usable()) {
            return Optional.empty();
        }
        return identities.outboundLineSubject(
                workspaceId, targetUserId, properties.ownerUserId());
    }

    @Override
    public boolean supportsStableDeliveryId() {
        return true;
    }

    @Override
    public void send(ReminderNotification notification) {
        String currentDestination = destinationFor(
                        notification.workspaceId(), notification.targetUserId())
                .filter(notification.destination()::equals)
                .orElseThrow(() -> new NotificationException(
                        "LINE notification destination is no longer authorized"));
        if (!client.push(currentDestination, notification.message(), notification.deliveryId())) {
            throw new NotificationException("LINE push request failed");
        }
    }
}

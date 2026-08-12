package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.application.ExternalIdentityService;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LineNotificationSenderTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock private ExternalIdentityService identities;
    @Mock private LineMessagingClient client;

    private LineNotificationSender sender;

    @BeforeEach
    void setUp() {
        LineProperties properties = new LineProperties(
                true,
                "channel-id",
                "channel-secret",
                "access-token",
                "configured-owner",
                "https://api.line.me",
                "https://api-data.line.me",
                "https://api.line.me/oauth2/v3/token",
                Duration.ofSeconds(5));
        sender = new LineNotificationSender(identities, client, properties);
    }

    @Test
    void sendsOnlyToTheCurrentAuthorizedDestination() {
        UUID deliveryId = UUID.randomUUID();
        when(identities.outboundLineSubject(WORKSPACE, ACTOR, "configured-owner"))
                .thenReturn(Optional.of("line-user-safe-fixture"));
        ReminderNotification notification = new ReminderNotification(
                WORKSPACE,
                ACTOR,
                deliveryId,
                "line-user-safe-fixture",
                null,
                null,
                "行程提醒",
                "行程時間快到了。");
        when(client.push("line-user-safe-fixture", notification.message(), deliveryId))
                .thenReturn(true);

        sender.send(notification);

        verify(client).push("line-user-safe-fixture", notification.message(), deliveryId);
        assertThat(sender.supportsStableDeliveryId()).isTrue();
    }

    @Test
    void revokedDestinationFailsClosedBeforePush() {
        when(identities.outboundLineSubject(WORKSPACE, ACTOR, "configured-owner"))
                .thenReturn(Optional.empty());
        ReminderNotification notification = new ReminderNotification(
                WORKSPACE,
                ACTOR,
                UUID.randomUUID(),
                "old-line-destination",
                null,
                null,
                "行程提醒",
                "行程時間快到了。");

        assertThatThrownBy(() -> sender.send(notification))
                .isInstanceOf(NotificationException.class);
    }
}

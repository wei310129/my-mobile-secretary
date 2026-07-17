package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Log sender 契約測試:通道識別正確、送出不丟例外。
 */
class LogNotificationSenderTest {

    private final LogNotificationSender sender = new LogNotificationSender();

    @Test
    void channelIsLog() {
        assertThat(sender.channel()).isEqualTo(NotificationChannel.LOG);
    }

    @Test
    void sendDoesNotThrow() {
        UUID workspaceId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        ReminderNotification notification =
                new ReminderNotification(workspaceId, userId, UUID.randomUUID(), "server-log",
                        1L, 2L, "買排骨", "ENTER geofence: 全聯");

        assertThatCode(() -> sender.send(notification)).doesNotThrowAnyException();
        assertThat(sender.destinationFor(workspaceId, userId)).contains("server-log");
        assertThat(sender.supportsStableDeliveryId()).isTrue();
    }
}

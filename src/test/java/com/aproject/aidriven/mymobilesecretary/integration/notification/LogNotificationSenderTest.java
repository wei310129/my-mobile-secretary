package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        ReminderNotification notification =
                new ReminderNotification(1L, 2L, "買排骨", "ENTER geofence: 全聯");

        assertThatCode(() -> sender.send(notification)).doesNotThrowAnyException();
    }
}

package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 的基本通知通道:把提醒印進 server log。
 * 永遠啟用——它同時是其他通道故障時的最後保底紀錄。
 */
@Component
public class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.LOG;
    }

    @Override
    public Optional<String> destinationFor(UUID workspaceId, UUID targetUserId) {
        return Optional.of("server-log");
    }

    @Override
    public boolean supportsStableDeliveryId() {
        return true;
    }

    /** 僅記不可逆識別與結構化 metadata；生活內容不進 server log。 */
    @Override
    public void send(ReminderNotification notification) {
        log.info("REMINDER [delivery={} workspace={} target={} reminder={} task={}]",
                SensitiveValueFingerprint.of(notification.deliveryId().toString()),
                SensitiveValueFingerprint.of(notification.workspaceId().toString()),
                SensitiveValueFingerprint.of(notification.targetUserId().toString()),
                notification.reminderId(), notification.taskId());
    }
}

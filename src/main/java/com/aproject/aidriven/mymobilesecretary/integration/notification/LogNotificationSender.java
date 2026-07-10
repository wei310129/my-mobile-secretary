package com.aproject.aidriven.mymobilesecretary.integration.notification;

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

    /** 用固定前綴輸出,方便 Phase 1E 生活測試時 grep「REMINDER」。 */
    @Override
    public void send(ReminderNotification notification) {
        log.info("REMINDER [reminder={} task={}] {} — {}",
                notification.reminderId(), notification.taskId(),
                notification.title(), notification.message());
    }
}

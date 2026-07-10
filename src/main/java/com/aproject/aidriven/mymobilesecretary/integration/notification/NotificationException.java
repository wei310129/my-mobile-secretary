package com.aproject.aidriven.mymobilesecretary.integration.notification;

/**
 * 通知送出失敗。
 *
 * 關鍵規則:這個例外只能被 ReminderDeliveryService 捕捉並記錄,
 * 絕不能往上傳到提醒核心——通知失敗不得拖垮提醒閉環。
 */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

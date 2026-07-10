package com.aproject.aidriven.mymobilesecretary.integration.notification;

/**
 * 要送給使用者的提醒通知內容(通道無關)。
 *
 * @param reminderId 對應的提醒紀錄
 * @param taskId     對應的任務
 * @param title      通知標題(任務標題)
 * @param message    通知內文(觸發原因,例如 "ENTER geofence: 全聯")
 */
public record ReminderNotification(
        Long reminderId,
        Long taskId,
        String title,
        String message
) {
}

package com.aproject.aidriven.mymobilesecretary.integration.notification;

/**
 * 通知通道的統一介面(Phase 1 為 log 與 Windows toast;之後換成 APNs 時後端其他程式不動)。
 *
 * 契約:
 * - send() 成功時正常返回;任何失敗一律丟 NotificationException,不得吞掉。
 * - channel() 回傳固定的通道識別,供送出紀錄使用。
 * - 實作必須無狀態或執行緒安全:同一實例會被多個請求並發呼叫。
 */
public interface NotificationSender {

    /** 這個 sender 代表的通道。 */
    NotificationChannel channel();

    /**
     * 送出通知。
     *
     * @throws NotificationException 送出失敗(由 ReminderDeliveryService 捕捉記錄)
     */
    void send(ReminderNotification notification);
}

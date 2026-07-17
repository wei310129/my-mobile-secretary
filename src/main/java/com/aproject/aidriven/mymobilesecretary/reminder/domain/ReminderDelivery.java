package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 一次提醒在單一通道上的送出結果。成功失敗都要留紀錄,供可靠度追查。
 */
@Entity
public class ReminderDelivery extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reminderId;

    /** 通道名(對應 NotificationChannel);存字串避免 domain 依賴 integration 模組的 enum。 */
    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private Instant sentAt;

    /** JPA 專用。 */
    protected ReminderDelivery() {
    }

    private ReminderDelivery(Long reminderId, String channel, boolean success, String errorMessage, Instant now) {
        this.reminderId = reminderId;
        this.channel = channel;
        this.success = success;
        this.errorMessage = errorMessage;
        this.sentAt = now;
    }

    /** 記錄一次成功送出。 */
    public static ReminderDelivery success(Long reminderId, String channel, Instant now) {
        return new ReminderDelivery(reminderId, channel, true, null, now);
    }

    /** 記錄一次失敗送出;錯誤訊息截斷到欄位長度內。 */
    public static ReminderDelivery failure(Long reminderId, String channel, String errorMessage, Instant now) {
        String truncated = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;
        return new ReminderDelivery(reminderId, channel, false, truncated, now);
    }

    public Long getId() {
        return id;
    }

    public Long getReminderId() {
        return reminderId;
    }

    public String getChannel() {
        return channel;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}

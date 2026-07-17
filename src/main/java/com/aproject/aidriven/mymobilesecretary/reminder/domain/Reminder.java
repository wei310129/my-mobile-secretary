package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 單次提醒紀錄:某個任務在某個時點、因某個原因被提醒。
 *
 * Phase 1A 只建立模型;實際由 geofence 命中或排程觸發是 Phase 1B/1C 的事。
 */
@Entity
public class Reminder extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderStatus status;

    private Instant triggeredAt;

    private Instant confirmedAt;

    /** 觸發原因,例如 "ENTER geofence: 全聯福利中心"。給使用者與除錯看。 */
    @Column(length = 200)
    private String triggerReason;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected Reminder() {
    }

    private Reminder(Long taskId, String triggerReason, Instant now) {
        this.taskId = taskId;
        this.status = ReminderStatus.TRIGGERED;
        this.triggeredAt = now;
        this.triggerReason = triggerReason;
        this.createdAt = now;
    }

    /** 建立一筆已觸發的提醒。 */
    public static Reminder triggered(Long taskId, String triggerReason, Instant now) {
        return new Reminder(taskId, triggerReason, now);
    }

    /**
     * 逾時未確認,升級催促。允許從 TRIGGERED 或 ESCALATED(重複催促)進入;
     * 已 CONFIRMED 的提醒不可再升級。
     */
    public void escalate(Instant now) {
        if (status == ReminderStatus.CONFIRMED) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Reminder %d is already confirmed and cannot be escalated".formatted(id));
        }
        this.status = ReminderStatus.ESCALATED;
    }

    /**
     * 使用者確認收到並處理了這次提醒。
     * 注意:確認「提醒」不等於確認「任務」完成——任務閉環由 /api/tasks/{id}/confirm 負責。
     */
    public void confirm(Instant now) {
        if (status == ReminderStatus.CONFIRMED) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Reminder %d is already confirmed".formatted(id));
        }
        this.status = ReminderStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public String getTriggerReason() {
        return triggerReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

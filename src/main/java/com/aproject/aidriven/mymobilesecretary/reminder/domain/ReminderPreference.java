package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalTime;

/** 單人提醒偏好：固定勿擾時段、臨時靜音，以及緊急任務是否例外。 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_reminder_preference_workspace", columnNames = "workspace_id"))
public class ReminderPreference extends WorkspaceOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private LocalTime quietStart;
    private LocalTime quietEnd;
    private Instant mutedUntil;

    @Column(nullable = false)
    private boolean allowHighPriority;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ReminderPreference() {
    }

    private ReminderPreference(Instant now) {
        this.allowHighPriority = true;
        this.updatedAt = now;
    }

    public static ReminderPreference create(Instant now) {
        return new ReminderPreference(now);
    }

    public void setQuietHours(LocalTime start, LocalTime end, boolean allowHighPriority, Instant now) {
        if (start == null || end == null || start.equals(end)) {
            throw new BusinessException("INVALID_QUIET_HOURS", "勿擾開始與結束時間必須不同");
        }
        this.quietStart = start;
        this.quietEnd = end;
        this.allowHighPriority = allowHighPriority;
        this.updatedAt = now;
    }

    public void clearQuietHours(Instant now) {
        this.quietStart = null;
        this.quietEnd = null;
        this.updatedAt = now;
    }

    public void muteUntil(Instant until, Instant now) {
        this.mutedUntil = until != null && until.isAfter(now) ? until : null;
        this.updatedAt = now;
    }

    public void resumeNow(Instant now) {
        this.mutedUntil = null;
        this.updatedAt = now;
    }

    public Integer getId() { return id; }
    public LocalTime getQuietStart() { return quietStart; }
    public LocalTime getQuietEnd() { return quietEnd; }
    public Instant getMutedUntil() { return mutedUntil; }
    public boolean isAllowHighPriority() { return allowHighPriority; }
    public Instant getUpdatedAt() { return updatedAt; }
}

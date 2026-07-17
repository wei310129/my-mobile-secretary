package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 行程結果追蹤詢問:一筆 = 一個行程「何時該問、問了沒、答了沒」。
 *
 * 關鍵規則:每行程最多一筆(DB unique);兩條觸發路徑競爭時取較早的 dueAt——
 * 「行程結束 +15 分」先建立,之後 GPS 離開若算出更早的時間點,用 advanceIfEarlier 提前,
 * 已發問(ASKED)後任何路徑都不得再改期。
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_schedule_follow_up_workspace_item",
        columnNames = {"workspace_id", "schedule_item_id"}))
public class ScheduleFollowUp extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleItemId;

    @Column(nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FollowUpStatus status;

    private Instant askedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected ScheduleFollowUp() {
    }

    private ScheduleFollowUp(Long scheduleItemId, Instant dueAt, Instant now) {
        this.scheduleItemId = scheduleItemId;
        this.dueAt = dueAt;
        this.status = FollowUpStatus.SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 為行程排定一次追蹤詢問,初始狀態 SCHEDULED。 */
    public static ScheduleFollowUp planAt(Long scheduleItemId, Instant dueAt, Instant now) {
        return new ScheduleFollowUp(scheduleItemId, dueAt, now);
    }

    /**
     * 另一條觸發路徑算出更早的發問時間 → 提前;較晚或已發問則不動。
     *
     * @return true 表示真的提前了
     */
    public boolean advanceIfEarlier(Instant candidateDueAt, Instant now) {
        if (status != FollowUpStatus.SCHEDULED || !candidateDueAt.isBefore(dueAt)) {
            return false;
        }
        this.dueAt = candidateDueAt;
        this.updatedAt = now;
        return true;
    }

    /** 已發問,等回報。 */
    public void markAsked(Instant now) {
        transitionTo(FollowUpStatus.ASKED, now);
        this.askedAt = now;
    }

    /** 使用者回報完成(還沒問就先回報也算——SCHEDULED 可直接跳 ANSWERED)。 */
    public void markAnswered(Instant now) {
        transitionTo(FollowUpStatus.ANSWERED, now);
    }

    private void transitionTo(FollowUpStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "FollowUp %d cannot transition from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleItemId() {
        return scheduleItemId;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public FollowUpStatus getStatus() {
        return status;
    }

    public Instant getAskedAt() {
        return askedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

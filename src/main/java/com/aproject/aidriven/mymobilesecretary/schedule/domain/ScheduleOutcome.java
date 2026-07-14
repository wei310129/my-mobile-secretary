package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 行程實際結果:使用者回報的「計畫 vs 實際」差異,緩衝規則的原始資料。
 *
 * 關鍵規則:準時與超時互斥——準時就不該有超時分鐘與原因;
 * 超時必須有正的分鐘數(原因可不填,使用者常只說「晚了半小時」)。
 */
@Entity
public class ScheduleOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long scheduleItemId;

    @Column(nullable = false)
    private boolean onTime;

    private Integer overrunMinutes;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OutcomeReason reason;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Instant recordedAt;

    /** JPA 專用。 */
    protected ScheduleOutcome() {
    }

    private ScheduleOutcome(Long scheduleItemId, boolean onTime, Integer overrunMinutes,
                            OutcomeReason reason, String note, Instant now) {
        this.scheduleItemId = scheduleItemId;
        this.onTime = onTime;
        this.overrunMinutes = overrunMinutes;
        this.reason = reason;
        this.note = note;
        this.recordedAt = now;
    }

    /** 準時完成:超時欄位一律清空,避免矛盾資料汙染緩衝統計。 */
    public static ScheduleOutcome onTime(Long scheduleItemId, String note, Instant now) {
        return new ScheduleOutcome(scheduleItemId, true, null, null, note, now);
    }

    /** 超時:分鐘數必須為正。 */
    public static ScheduleOutcome overrun(Long scheduleItemId, int overrunMinutes,
                                          OutcomeReason reason, String note, Instant now) {
        if (overrunMinutes <= 0) {
            throw new BusinessException("INVALID_OVERRUN", "overrunMinutes must be positive");
        }
        return new ScheduleOutcome(scheduleItemId, false, overrunMinutes, reason, note, now);
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleItemId() {
        return scheduleItemId;
    }

    public boolean isOnTime() {
        return onTime;
    }

    public Integer getOverrunMinutes() {
        return overrunMinutes;
    }

    public OutcomeReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

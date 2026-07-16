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
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 行程:有時間(可選地點)的承諾。後端行程是 source of truth。
 *
 * 關鍵規則:狀態只能透過行為方法改變,非法轉換丟 BusinessException;
 * 「可行才放行」的驗算在 planner 的 FeasibilityService,這裡只管狀態一致性。
 */
@Entity
public class ScheduleItem {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    /** 行程地點;線上會議等無地點行程為 null(無地點就不做交通可行性檢查)。 */
    private Long placeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    /** 重複週期;固定行程結束後由 rollover worker 自動排下一次。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Recurrence recurrence = Recurrence.NONE;

    /** 固定行程的最後有效日期（台北時區、含當日）；null 代表無期限。 */
    private LocalDate recurrenceUntil;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected ScheduleItem() {
    }

    private ScheduleItem(String title, Instant startAt, Instant endAt, Long placeId, Instant now) {
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException("INVALID_TIME_RANGE", "endAt must be after startAt");
        }
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.placeId = placeId;
        this.status = ScheduleStatus.PROPOSED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 提出新行程,初始狀態一律 PROPOSED(等可行性驗算)。 */
    public static ScheduleItem propose(String title, Instant startAt, Instant endAt, Long placeId, Instant now) {
        return new ScheduleItem(title, startAt, endAt, placeId, now);
    }

    /** 重複週期。 */
    public enum Recurrence {
        /** 單次行程。 */
        NONE,
        /** 每週固定(結束後自動排下一週)。 */
        WEEKLY,
        /** 每個週一至週五(結束後自動排下一個平日)。 */
        WEEKDAYS
    }

    /** 設為每週固定行程。終止狀態(取消/放棄)的行程沒有下一週,不可設定。 */
    public void repeatWeekly(Instant now) {
        repeat(Recurrence.WEEKLY, now);
    }

    /** 設定固定週期。終止狀態的行程不可重新啟用固定規則。 */
    public void repeat(Recurrence recurrence, Instant now) {
        repeat(recurrence, null, now);
    }

    /** 設定固定週期與含當日的截止日期。 */
    public void repeat(Recurrence recurrence, LocalDate recurrenceUntil, Instant now) {
        if (status == ScheduleStatus.CANCELED || status == ScheduleStatus.REJECTED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Schedule %d is terminated (%s), cannot repeat".formatted(id, status));
        }
        if (recurrence == null || recurrence == Recurrence.NONE) {
            stopRepeating(now);
            return;
        }
        LocalDate firstOccurrence = startAt.atZone(TAIPEI).toLocalDate();
        if (recurrenceUntil != null && recurrenceUntil.isBefore(firstOccurrence)) {
            throw new BusinessException("INVALID_RECURRENCE_UNTIL",
                    "recurrenceUntil must include the first occurrence");
        }
        this.recurrence = recurrence;
        this.recurrenceUntil = recurrenceUntil;
        this.updatedAt = now;
    }

    /** 取消固定(之後不再自動排下一週)。 */
    public void stopRepeating(Instant now) {
        this.recurrence = Recurrence.NONE;
        this.recurrenceUntil = null;
        this.updatedAt = now;
    }

    /** 固定行程自然走到截止日；停止展開，但保留截止日期供查詢與稽核。 */
    public void finishRecurrence(Instant now) {
        this.recurrence = Recurrence.NONE;
        this.updatedAt = now;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public LocalDate getRecurrenceUntil() {
        return recurrenceUntil;
    }

    /** 確認(可行放行,或使用者看過警告後強制確認)。 */
    public void confirm(Instant now) {
        transitionTo(ScheduleStatus.CONFIRMED, now);
    }

    /** 暫無想法 → 停進 pending 池。 */
    public void park(Instant now) {
        transitionTo(ScheduleStatus.PENDING, now);
    }

    /** 不可行且放棄。 */
    public void reject(Instant now) {
        transitionTo(ScheduleStatus.REJECTED, now);
    }

    /** 取消。 */
    public void cancel(Instant now) {
        transitionTo(ScheduleStatus.CANCELED, now);
    }

    /** 完成。 */
    public void complete(Instant now) {
        transitionTo(ScheduleStatus.COMPLETED, now);
    }

    /**
     * 改時間 → 回到 PROPOSED 重新過可行性把關。
     * PENDING 的行程被安排時也走這裡。
     */
    public void reschedule(Instant newStartAt, Instant newEndAt, Instant now) {
        if (!newEndAt.isAfter(newStartAt)) {
            throw new BusinessException("INVALID_TIME_RANGE", "endAt must be after startAt");
        }
        if (recurrence != Recurrence.NONE && recurrenceUntil != null
                && newStartAt.atZone(TAIPEI).toLocalDate().isAfter(recurrenceUntil)) {
            throw new BusinessException("INVALID_RECURRENCE_UNTIL",
                    "A recurring occurrence cannot move after recurrenceUntil");
        }
        transitionTo(ScheduleStatus.PROPOSED, now);
        this.startAt = newStartAt;
        this.endAt = newEndAt;
    }

    /** 修改行程地點後要重新驗算交通可行性,因此回到 PROPOSED。 */
    public void changePlace(Long newPlaceId, Instant now) {
        transitionTo(ScheduleStatus.PROPOSED, now);
        this.placeId = newPlaceId;
    }

    private void transitionTo(ScheduleStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Schedule %d cannot transition from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

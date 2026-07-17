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
 * 任務:提醒閉環的主體。
 *
 * 關鍵規則:狀態只能透過本類的行為方法改變(confirm/cancel/...),
 * 每次轉換都先問 TaskStatus 是否合法,非法轉換一律丟 BusinessException。
 */
@Entity
public class Task extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskPriority priority;

    /** 生活領域分類,供「今天只列工作相關」等查詢使用。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category = Category.OTHER;

    /** 待辦／提醒的重複週期。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Recurrence recurrence = Recurrence.NONE;

    /** 暫停週期規則時保留下一次時間,但不排入提醒佇列。 */
    @Column(nullable = false)
    private boolean recurrencePaused;

    /** 到期時是否還要先檢查外部條件。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConditionType conditionType = ConditionType.NONE;

    /** 條件參數。格式由 application 層建立與解讀,domain 只保存。 */
    @Column(length = 1000)
    private String conditionPayload;

    private Instant dueAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected Task() {
    }

    private Task(String title, String description, TaskPriority priority, Instant dueAt, Instant now) {
        this(title, description, priority, dueAt, Category.OTHER, Recurrence.NONE,
                ConditionType.NONE, null, now);
    }

    private Task(String title, String description, TaskPriority priority, Instant dueAt,
                 Category category, Recurrence recurrence,
                 ConditionType conditionType, String conditionPayload, Instant now) {
        this.title = title;
        this.description = description;
        this.status = TaskStatus.CREATED;
        this.priority = priority;
        this.category = category == null ? Category.OTHER : category;
        this.recurrence = recurrence == null ? Recurrence.NONE : recurrence;
        this.recurrencePaused = false;
        this.conditionType = conditionType == null ? ConditionType.NONE : conditionType;
        this.conditionPayload = conditionPayload;
        this.dueAt = dueAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 建立新任務,初始狀態一律 CREATED。
     *
     * @param now 由呼叫端從 Clock 取得,domain 不自己拿系統時間
     */
    public static Task create(String title, String description, TaskPriority priority, Instant dueAt, Instant now) {
        return new Task(title, description, priority, dueAt, now);
    }

    /** 建立含分類、週期或外部條件的任務。 */
    public static Task create(String title, String description, TaskPriority priority, Instant dueAt,
                              Category category, Recurrence recurrence,
                              ConditionType conditionType, String conditionPayload, Instant now) {
        return new Task(title, description, priority, dueAt, category, recurrence,
                conditionType, conditionPayload, now);
    }

    public enum Category {
        WORK,
        PERSONAL,
        SHOPPING,
        HEALTH,
        FINANCE,
        OTHER
    }

    public enum Recurrence {
        NONE,
        DAILY,
        WEEKLY,
        MONTHLY
    }

    public enum ConditionType {
        NONE,
        RAIN,
        TRAFFIC
    }

    /**
     * 使用者確認任務完成 → CONFIRMED(終止狀態)。
     * 已 CONFIRMED 或 CANCELED 的任務再確認會被拒絕。
     */
    public void confirm(Instant now) {
        transitionTo(TaskStatus.CONFIRMED, now);
    }

    /**
     * 使用者取消任務 → CANCELED(終止狀態),之後不再追蹤與提醒。
     */
    public void cancel(Instant now) {
        transitionTo(TaskStatus.CANCELED, now);
    }

    /**
     * 改期限(使用者「拿包裹改成今天11點」)。
     * 已結案(CONFIRMED/CANCELED)的任務改期限沒有意義,一律拒絕。
     */
    public void changeDueAt(Instant newDueAt, Instant now) {
        if (status == TaskStatus.CONFIRMED || status == TaskStatus.CANCELED) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Task %d is closed (%s), dueAt cannot change".formatted(id, status));
        }
        this.dueAt = newDueAt;
        this.updatedAt = now;
    }

    /** 修改待辦本身;null 代表該欄不變,空白標題一律拒絕。 */
    public void updateDetails(String newTitle, String newDescription,
                              TaskPriority newPriority, Category newCategory, Instant now) {
        if (status == TaskStatus.CONFIRMED || status == TaskStatus.CANCELED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Task %d is closed (%s), details cannot change".formatted(id, status));
        }
        if (newTitle != null) {
            if (newTitle.isBlank()) {
                throw new BusinessException("INVALID_TASK_TITLE", "Task title cannot be blank");
            }
            this.title = newTitle.strip();
        }
        if (newDescription != null) {
            this.description = newDescription.isBlank() ? null : newDescription.strip();
        }
        if (newPriority != null) {
            this.priority = newPriority;
        }
        if (newCategory != null) {
            this.category = newCategory;
        }
        this.updatedAt = now;
    }

    public void pauseRecurrence(Instant now) {
        requireRecurring();
        this.recurrencePaused = true;
        this.updatedAt = now;
    }

    public void resumeRecurrence(Instant now) {
        requireRecurring();
        this.recurrencePaused = false;
        this.updatedAt = now;
    }

    private void requireRecurring() {
        if (recurrence == Recurrence.NONE) {
            throw new BusinessException("TASK_NOT_RECURRING", "Task %d is not recurring".formatted(id));
        }
        if (status == TaskStatus.CANCELED || status == TaskStatus.CONFIRMED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Task %d is closed (%s)".formatted(id, status));
        }
    }

    /**
     * 重複任務完成一輪或送出本輪提醒後,移到下一次並保持開啟。
     * 這不是一般狀態轉換:它代表同一條週期規則的新 occurrence。
     */
    public void advanceOccurrence(Instant nextDueAt, Instant now) {
        if (recurrence == Recurrence.NONE) {
            throw new BusinessException("TASK_NOT_RECURRING", "Task %d is not recurring".formatted(id));
        }
        if (status == TaskStatus.CANCELED || status == TaskStatus.CONFIRMED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Task %d is closed (%s), cannot advance recurrence".formatted(id, status));
        }
        this.dueAt = nextDueAt;
        this.status = TaskStatus.SCHEDULED;
        this.updatedAt = now;
    }

    /**
     * 已對使用者送出提醒 → REMINDED。
     * REMINDED → REMINDED 合法(debounce 視窗過後的重新提醒)。
     */
    public void remind(Instant now) {
        transitionTo(TaskStatus.REMINDED, now);
    }

    /** 任務是否還能接收提醒(已確認/取消的任務不再提醒)。 */
    public boolean canBeReminded() {
        return status.canTransitionTo(TaskStatus.REMINDED);
    }

    /**
     * 提醒後未確認,升級再提醒 → ESCALATED。
     * ESCALATED → ESCALATED 合法(第 2、3 次催促)。
     */
    public void escalate(Instant now) {
        transitionTo(TaskStatus.ESCALATED, now);
    }

    /** 任務是否可被升級催促(只有 REMINDED/ESCALATED 可以;已結案或還沒提醒過的不行)。 */
    public boolean canBeEscalated() {
        return status.canTransitionTo(TaskStatus.ESCALATED);
    }

    /** 集中的轉換守門:非法轉換丟 INVALID_STATE_TRANSITION。 */
    private void transitionTo(TaskStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Task %d cannot transition from %s to %s".formatted(id, status, target));
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

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public Category getCategory() {
        return category;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public boolean isRecurrencePaused() {
        return recurrencePaused;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public String getConditionPayload() {
        return conditionPayload;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

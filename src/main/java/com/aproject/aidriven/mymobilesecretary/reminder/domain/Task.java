package com.aproject.aidriven.mymobilesecretary.reminder.domain;

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
 * 任務:提醒閉環的主體。
 *
 * 關鍵規則:狀態只能透過本類的行為方法改變(confirm/cancel/...),
 * 每次轉換都先問 TaskStatus 是否合法,非法轉換一律丟 BusinessException。
 */
@Entity
public class Task {

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

    private Instant dueAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected Task() {
    }

    private Task(String title, String description, TaskPriority priority, Instant dueAt, Instant now) {
        this.title = title;
        this.description = description;
        this.status = TaskStatus.CREATED;
        this.priority = priority;
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

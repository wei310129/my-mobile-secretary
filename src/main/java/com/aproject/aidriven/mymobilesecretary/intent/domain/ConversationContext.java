package com.aproject.aidriven.mymobilesecretary.intent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 單人模式的短期對話指代狀態。
 *
 * 只保存「上一個／第二個／那件事」所需的最小識別資訊,不把完整聊天內容當業務資料。
 * Phase 5 多使用者化時再按使用者拆分;目前固定 id=1。
 */
@Entity
public class ConversationContext {

    @Id
    private Integer id;

    private Long lastTaskId;
    private Long lastScheduleId;
    private Long lastPlaceId;

    @Column(length = 2000)
    private String lastTaskListIds;

    @Column(length = 2000)
    private String lastScheduleListIds;

    @Column(length = 60)
    private String lastAction;

    @Column(length = 500)
    private String lastUserText;

    @Column(length = 2000)
    private String lastAssistantText;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConversationContext() {
    }

    private ConversationContext(Instant now) {
        this.id = 1;
        this.updatedAt = now;
    }

    public static ConversationContext create(Instant now) {
        return new ConversationContext(now);
    }

    public void rememberExchange(String action, String userText, String assistantText, Instant now) {
        this.lastAction = action;
        this.lastUserText = truncate(userText, 500);
        this.lastAssistantText = truncate(assistantText, 2000);
        this.updatedAt = now;
    }

    public void rememberTask(Long taskId, Instant now) {
        this.lastTaskId = taskId;
        this.updatedAt = now;
    }

    public void rememberSchedule(Long scheduleId, Instant now) {
        this.lastScheduleId = scheduleId;
        this.updatedAt = now;
    }

    public void rememberPlace(Long placeId, Instant now) {
        this.lastPlaceId = placeId;
        this.updatedAt = now;
    }

    public void rememberTaskList(String ids, Instant now) {
        this.lastTaskListIds = ids;
        this.updatedAt = now;
    }

    public void rememberScheduleList(String ids, Instant now) {
        this.lastScheduleListIds = ids;
        this.updatedAt = now;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public Long getLastTaskId() { return lastTaskId; }
    public Long getLastScheduleId() { return lastScheduleId; }
    public Long getLastPlaceId() { return lastPlaceId; }
    public String getLastTaskListIds() { return lastTaskListIds; }
    public String getLastScheduleListIds() { return lastScheduleListIds; }
    public String getLastAction() { return lastAction; }
    public String getLastUserText() { return lastUserText; }
    public String getLastAssistantText() { return lastAssistantText; }
    public Instant getUpdatedAt() { return updatedAt; }
}

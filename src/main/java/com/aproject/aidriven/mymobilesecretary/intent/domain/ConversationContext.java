package com.aproject.aidriven.mymobilesecretary.intent.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

/**
 * 單人模式的短期對話指代狀態。
 *
 * 只保存「上一個／第二個／那件事」所需的最小識別資訊,不把完整聊天內容當業務資料。
 * Phase 5 多使用者化時再按使用者拆分;目前固定 id=1。
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_conversation_context_scope",
        columnNames = {"workspace_id", "created_by_user_id", "channel", "conversation_scope_digest"}))
public class ConversationContext extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, length = 64)
    private String conversationScopeDigest;

    @Column(name = "scope_key_version", nullable = false)
    private Integer scopeKeyVersion;

    @Column(name = "conversation_focus_id")
    private UUID conversationFocusId;

    private Long lastTaskId;
    private Long lastScheduleId;
    private Long lastPlaceId;

    @Column(length = 2000)
    private String lastTaskListIds;

    @Column(length = 2000)
    private String lastScheduleListIds;

    @Column(length = 2000)
    private String lastObjectAnnotationListIds;

    private Long pendingObjectAnnotationDeleteId;

    @Column(length = 60)
    private String lastAction;

    @Column(length = 500)
    private String lastUserText;

    @Column(length = 2000)
    private String lastAssistantText;

    @Column(nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean taskListTouched;

    @Transient
    private boolean scheduleListTouched;

    @Transient
    private boolean objectAnnotationStateTouched;

    protected ConversationContext() {
    }

    private ConversationContext(WorkspaceChannel channel, ConversationScopeKey scopeKey,
                                UUID conversationFocusId, Instant now) {
        this.channel = channel;
        this.conversationScopeDigest = scopeKey.digest();
        this.scopeKeyVersion = scopeKey.keyVersion();
        this.conversationFocusId = conversationFocusId;
        this.updatedAt = now;
    }

    public static ConversationContext create(WorkspaceChannel channel, ConversationScopeKey scopeKey,
                                             Instant now) {
        return create(channel, scopeKey, null, now);
    }

    public static ConversationContext create(WorkspaceChannel channel, ConversationScopeKey scopeKey,
                                             UUID conversationFocusId, Instant now) {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        if (scopeKey == null) {
            throw new IllegalArgumentException("conversation scope key is required");
        }
        return new ConversationContext(channel, scopeKey, conversationFocusId, now);
    }

    public void migrateScope(ConversationScopeKey scopeKey, Instant now) {
        this.conversationScopeDigest = scopeKey.digest();
        this.scopeKeyVersion = scopeKey.keyVersion();
        this.updatedAt = now;
    }

    public void rememberExchange(String action, String userText, String assistantText, Instant now) {
        rememberExchange(action, userText, assistantText, now,
                taskListTouched, scheduleListTouched, objectAnnotationStateTouched);
    }

    public void rememberExchange(
            String action, String userText, String assistantText, Instant now,
            boolean retainTaskList, boolean retainScheduleList,
            boolean retainObjectAnnotationState) {
        if (!retainTaskList && !taskListTouched) {
            this.lastTaskListIds = null;
        }
        if (!retainScheduleList && !scheduleListTouched) {
            this.lastScheduleListIds = null;
        }
        if (!retainObjectAnnotationState && !objectAnnotationStateTouched) {
            this.lastObjectAnnotationListIds = null;
            this.pendingObjectAnnotationDeleteId = null;
        }
        this.lastAction = action;
        this.lastUserText = truncate(userText, 500);
        this.lastAssistantText = truncate(assistantText, 2000);
        this.updatedAt = now;
        this.taskListTouched = false;
        this.scheduleListTouched = false;
        this.objectAnnotationStateTouched = false;
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
        this.taskListTouched = true;
        this.updatedAt = now;
    }

    public void rememberScheduleList(String ids, Instant now) {
        this.lastScheduleListIds = ids;
        this.scheduleListTouched = true;
        this.updatedAt = now;
    }

    public void rememberObjectAnnotationList(String ids, Instant now) {
        this.lastObjectAnnotationListIds = ids;
        this.pendingObjectAnnotationDeleteId = null;
        this.objectAnnotationStateTouched = true;
        this.updatedAt = now;
    }

    public void prepareObjectAnnotationDelete(Long annotationId, Instant now) {
        this.pendingObjectAnnotationDeleteId = annotationId;
        this.objectAnnotationStateTouched = true;
        this.updatedAt = now;
    }

    public void clearObjectAnnotationDelete(Instant now) {
        this.pendingObjectAnnotationDeleteId = null;
        this.objectAnnotationStateTouched = true;
        this.updatedAt = now;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public Long getLastTaskId() { return lastTaskId; }
    public Integer getId() { return id; }
    public WorkspaceChannel getChannel() { return channel; }
    public String getConversationScopeDigest() { return conversationScopeDigest; }
    public Integer getScopeKeyVersion() { return scopeKeyVersion; }
    public UUID getConversationFocusId() { return conversationFocusId; }
    public Long getLastScheduleId() { return lastScheduleId; }
    public Long getLastPlaceId() { return lastPlaceId; }
    public String getLastTaskListIds() { return lastTaskListIds; }
    public String getLastScheduleListIds() { return lastScheduleListIds; }
    public String getLastObjectAnnotationListIds() { return lastObjectAnnotationListIds; }
    public Long getPendingObjectAnnotationDeleteId() { return pendingObjectAnnotationDeleteId; }
    public String getLastAction() { return lastAction; }
    public String getLastUserText() { return lastUserText; }
    public String getLastAssistantText() { return lastAssistantText; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A durable root work item; it deliberately has only one nullable activity focus. */
@Entity
@Table(name = "conversation_focus")
public class ConversationFocus extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;

    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ConversationFocusRootKind rootKind;

    @Column(nullable = false, updatable = false, length = 60)
    private String rootDomain;

    @Column(length = 200)
    private String routingKey;

    private UUID workflowId;

    @Column(nullable = false, length = 200)
    private String safeLabel;

    @Column(length = 80)
    private String activityCode;

    @Column(length = 200)
    private String activityLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationFocusStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ConversationFocusCloseReason closeReason;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConversationFocus() {
    }

    private ConversationFocus(ConversationScopeKey scopeKey, WorkspaceChannel channel,
                              ConversationFocusRootKind rootKind, String rootDomain,
                              String routingKey, UUID workflowId, String safeLabel, Instant now) {
        this.id = UUID.randomUUID();
        this.conversationScopeDigest = scopeKey.digest();
        this.scopeKeyVersion = scopeKey.keyVersion();
        this.channel = channel;
        this.rootKind = rootKind;
        this.rootDomain = required(rootDomain, "root domain", 60);
        this.routingKey = routingKey;
        this.workflowId = workflowId;
        this.safeLabel = required(safeLabel, "safe label", 200);
        this.status = ConversationFocusStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        validateAnchor();
    }

    public static ConversationFocus resource(ConversationScopeKey scopeKey, WorkspaceChannel channel,
                                             String rootDomain, String routingKey, String safeLabel,
                                             Instant now) {
        return new ConversationFocus(scopeKey, channel, ConversationFocusRootKind.RESOURCE,
                rootDomain, required(routingKey, "routing key", 200), null, safeLabel, now);
    }

    public static ConversationFocus workflow(ConversationScopeKey scopeKey, WorkspaceChannel channel,
                                             ConversationFocusRootKind rootKind, String rootDomain,
                                             UUID workflowId, String safeLabel, Instant now) {
        if (rootKind == ConversationFocusRootKind.RESOURCE) {
            throw new IllegalArgumentException("resource focus requires a routing key");
        }
        return new ConversationFocus(scopeKey, channel, rootKind, rootDomain, null,
                java.util.Objects.requireNonNull(workflowId, "workflow id"), safeLabel, now);
    }

    public void changeActivity(String code, String label, Instant now) {
        requireOpen();
        if ((code == null) != (label == null)) {
            throw new IllegalArgumentException("activity code and label must change together");
        }
        this.activityCode = code == null ? null : required(code, "activity code", 80);
        this.activityLabel = label == null ? null : required(label, "activity label", 200);
        this.updatedAt = now;
    }

    public void initializeActivity(String code, String label) {
        if (activityCode != null || activityLabel != null) {
            throw new IllegalStateException("focus activity is already initialized");
        }
        if (code == null && label == null) return;
        if ((code == null) != (label == null)) {
            throw new IllegalArgumentException(
                    "activity code and label must initialize together");
        }
        this.activityCode = required(code, "activity code", 80);
        this.activityLabel = required(label, "activity label", 200);
    }

    public void suspend(Instant now) { requireOpen(); status = ConversationFocusStatus.SUSPENDED; updatedAt = now; }
    public void resume(Instant now) { resume(now, null); }
    public void resume(Instant now, String refreshedSafeLabel) { if (status != ConversationFocusStatus.SUSPENDED) throw new IllegalStateException("only suspended focus can resume"); if (refreshedSafeLabel != null) safeLabel = required(refreshedSafeLabel, "safe label", 200); status = ConversationFocusStatus.ACTIVE; updatedAt = now; }
    public void close(ConversationFocusCloseReason reason, Instant now) { requireOpen(); status = ConversationFocusStatus.CLOSED; closeReason = java.util.Objects.requireNonNull(reason); updatedAt = now; }

    private void requireOpen() { if (status == ConversationFocusStatus.CLOSED) throw new IllegalStateException("closed focus cannot transition"); }
    private void validateAnchor() { if ((rootKind == ConversationFocusRootKind.RESOURCE) != (routingKey != null && workflowId == null)) throw new IllegalArgumentException("focus anchor must use exactly one resource or workflow identity"); }
    private static String required(String value, String label, int max) { if (value == null || value.isBlank() || value.strip().length() > max) throw new IllegalArgumentException(label + " is required"); return value.strip(); }
    public UUID getId() { return id; } public ConversationFocusStatus getStatus() { return status; } public ConversationFocusRootKind getRootKind() { return rootKind; } public String getRootDomain() { return rootDomain; } public String getRoutingKey() { return routingKey; } public UUID getWorkflowId() { return workflowId; } public String getSafeLabel() { return safeLabel; } public String getActivityCode() { return activityCode; } public String getActivityLabel() { return activityLabel; } public ConversationFocusCloseReason getCloseReason() { return closeReason; } public String getConversationScopeDigest() { return conversationScopeDigest; } public Integer getScopeKeyVersion() { return scopeKeyVersion; } public WorkspaceChannel getChannel() { return channel; }
}

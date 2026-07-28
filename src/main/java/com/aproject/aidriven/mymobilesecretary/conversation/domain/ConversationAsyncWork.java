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

/** Durable, actor-private record of an asynchronous result that must not own chat focus. */
@Entity
@Table(name = "conversation_async_work")
public class ConversationAsyncWork extends WorkspaceOwnedEntity {

    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;
    @Column(name = "work_type", nullable = false, updatable = false, length = 60)
    private String workType;
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;
    @Column(name = "safe_label", nullable = false, updatable = false, length = 200)
    private String safeLabel;
    @Column(name = "inbound_idempotency_hmac", nullable = false, updatable = false, length = 64)
    private String inboundIdempotencyHmac;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ConversationAsyncWorkStatus status;
    @Version private Long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant terminalAt;

    protected ConversationAsyncWork() { }

    private ConversationAsyncWork(ConversationScopeKey scope, WorkspaceChannel channel, String workType,
                                  UUID workflowId, String safeLabel, String inboundHmac, Instant now) {
        id = UUID.randomUUID(); this.channel = channel; conversationScopeDigest = scope.digest();
        scopeKeyVersion = scope.keyVersion(); this.workType = required(workType, "work type", 60);
        this.workflowId = java.util.Objects.requireNonNull(workflowId, "workflowId");
        this.safeLabel = required(safeLabel, "safe label", 200);
        inboundIdempotencyHmac = requiredHmac(inboundHmac); status = ConversationAsyncWorkStatus.PENDING;
        createdAt = now; updatedAt = now;
    }

    public static ConversationAsyncWork pending(ConversationScopeKey scope, WorkspaceChannel channel,
                                                String workType, UUID workflowId, String safeLabel,
                                                String inboundHmac, Instant now) {
        return new ConversationAsyncWork(scope, channel, workType, workflowId, safeLabel, inboundHmac, now);
    }

    public boolean succeed(Instant now) { return terminal(ConversationAsyncWorkStatus.SUCCEEDED, now); }
    public boolean fail(Instant now) { return terminal(ConversationAsyncWorkStatus.FAILED, now); }
    private boolean terminal(ConversationAsyncWorkStatus terminalStatus, Instant now) {
        if (status != ConversationAsyncWorkStatus.PENDING) return false;
        status = terminalStatus; updatedAt = now; terminalAt = now; return true;
    }
    public UUID getId() { return id; }
    public UUID getWorkflowId() { return workflowId; }
    public String getSafeLabel() { return safeLabel; }
    public ConversationAsyncWorkStatus getStatus() { return status; }
    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
    private static String requiredHmac(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("inbound idempotency hmac must be a SHA-256 hex value");
        return value;
    }
}

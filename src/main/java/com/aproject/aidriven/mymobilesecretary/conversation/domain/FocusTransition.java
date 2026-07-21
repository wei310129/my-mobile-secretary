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

/** Immutable transition audit. Delivery state belongs to terminal reply/outbox, never here. */
@Entity
@Table(name = "focus_transition")
public class FocusTransition extends WorkspaceOwnedEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40) private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64) private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false) private Integer scopeKeyVersion;
    @Column(nullable = false, updatable = false) private long beforeRevision;
    @Column(nullable = false, updatable = false) private long afterRevision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30) private FocusTransitionType type;
    private UUID fromFocusId; private UUID toFocusId;
    @Column(name = "inbound_idempotency_hmac", nullable = false, updatable = false, length = 64) private String inboundIdempotencyHmac;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected FocusTransition() { }
    private FocusTransition(ConversationScopeKey scope, WorkspaceChannel channel, long before, long after, FocusTransitionType type, UUID from, UUID to, String inboundHmac, Instant now) { id=UUID.randomUUID(); conversationScopeDigest=scope.digest(); scopeKeyVersion=scope.keyVersion(); this.channel=channel; beforeRevision=before; afterRevision=after; this.type=type; fromFocusId=from; toFocusId=to; inboundIdempotencyHmac=required(inboundHmac); createdAt=now; }
    public static FocusTransition create(ConversationScopeKey scope, WorkspaceChannel channel, long before, long after, FocusTransitionType type, UUID from, UUID to, String inboundHmac, Instant now) { if (after != before + 1) throw new IllegalArgumentException("persisted transition advances revision exactly once"); return new FocusTransition(scope, channel, before, after, type, from, to, inboundHmac, now); }
    private static String required(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("inbound idempotency HMAC is required"); return value; }
    public FocusTransitionType getType() { return type; } public long getAfterRevision() { return afterRevision; } public UUID getFromFocusId() { return fromFocusId; } public UUID getToFocusId() { return toFocusId; }
}

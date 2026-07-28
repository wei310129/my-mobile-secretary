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

/** A single unresolved proposal fenced by the focus-head revision. */
@Entity
@Table(name = "pending_focus_transition")
public class PendingFocusTransition extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;

    @Column(name = "scope_key_version", updatable = false)
    private Integer scopeKeyVersion;

    @Column(nullable = false, updatable = false)
    private long baseFocusRevision;

    @Column(name = "from_focus_id", updatable = false)
    private UUID fromFocusId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_type", updatable = false, length = 30)
    private FocusTransitionType transitionType;

    @Column(name = "root_domain", updatable = false, length = 60)
    private String rootDomain;

    @Column(name = "candidate_workflow_id", updatable = false)
    private UUID candidateWorkflowId;

    @Column(name = "candidate_safe_label", updatable = false, length = 200)
    private String candidateSafeLabel;

    @Column(name = "inbound_idempotency_hmac", updatable = false, length = 64)
    private String inboundIdempotencyHmac;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PendingFocusTransitionStatus status;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PendingFocusTransition() {
    }

    private PendingFocusTransition(ConversationScopeKey scope, WorkspaceChannel channel,
                                   long baseFocusRevision, UUID fromFocusId,
                                   PendingFocusCandidate candidate, String inboundIdempotencyHmac,
                                   Instant now) {
        this.id = UUID.randomUUID();
        this.channel = channel;
        this.conversationScopeDigest = scope.digest();
        this.scopeKeyVersion = scope.keyVersion();
        this.baseFocusRevision = baseFocusRevision;
        this.fromFocusId = fromFocusId;
        this.transitionType = candidate.transitionType();
        this.rootDomain = candidate.rootDomain();
        this.candidateWorkflowId = candidate.workflowId();
        this.candidateSafeLabel = candidate.safeLabel();
        this.inboundIdempotencyHmac = requiredHmac(inboundIdempotencyHmac);
        this.status = PendingFocusTransitionStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static PendingFocusTransition pending(ConversationScopeKey scope, WorkspaceChannel channel,
                                                 long baseFocusRevision, UUID fromFocusId,
                                                 PendingFocusCandidate candidate,
                                                 String inboundIdempotencyHmac, Instant now) {
        if (baseFocusRevision < 0) {
            throw new IllegalArgumentException("base focus revision must not be negative");
        }
        return new PendingFocusTransition(scope, channel, baseFocusRevision, fromFocusId,
                java.util.Objects.requireNonNull(candidate, "candidate"), inboundIdempotencyHmac, now);
    }

    public boolean expireIfRevisionChanged(long currentRevision, Instant now) {
        if (status == PendingFocusTransitionStatus.PENDING && baseFocusRevision != currentRevision) {
            status = PendingFocusTransitionStatus.EXPIRED;
            updatedAt = now;
            return true;
        }
        return status == PendingFocusTransitionStatus.EXPIRED;
    }

    public boolean expireIfIncompatibleScope(ConversationScopeKey scope, Instant now) {
        if (status == PendingFocusTransitionStatus.PENDING
                && (scopeKeyVersion == null || scopeKeyVersion != scope.keyVersion())) {
            status = PendingFocusTransitionStatus.EXPIRED;
            updatedAt = now;
            return true;
        }
        return status == PendingFocusTransitionStatus.EXPIRED;
    }

    public void accept(Instant now) {
        if (status != PendingFocusTransitionStatus.PENDING) {
            throw new IllegalStateException("pending focus transition is no longer available");
        }
        status = PendingFocusTransitionStatus.ACCEPTED;
        updatedAt = now;
    }

    public void reject(Instant now) {
        if (status == PendingFocusTransitionStatus.PENDING) {
            status = PendingFocusTransitionStatus.REJECTED;
            updatedAt = now;
        }
    }

    public PendingFocusTransitionStatus getStatus() {
        return status;
    }

    public UUID getId() { return id; }
    public UUID getFromFocusId() { return fromFocusId; }
    public String getConversationScopeDigest() { return conversationScopeDigest; }
    public Integer getScopeKeyVersion() { return scopeKeyVersion; }
    public long getBaseFocusRevision() { return baseFocusRevision; }
    public String getInboundIdempotencyHmac() { return inboundIdempotencyHmac; }
    public PendingFocusCandidate getCandidate() {
        if (transitionType == null || rootDomain == null || candidateWorkflowId == null || candidateSafeLabel == null) {
            throw new IllegalStateException("pending focus transition has no trusted candidate");
        }
        return new PendingFocusCandidate(transitionType, rootDomain, candidateWorkflowId, candidateSafeLabel);
    }

    private static String requiredHmac(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inbound idempotency hmac must be a SHA-256 hex value");
        }
        return value;
    }
}

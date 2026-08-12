package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Actor-owned pointer to exactly one unresolved typed question; it never stores user text or slots. */
@Entity
@Table(name = "conversation_pending_question")
public class ConversationPendingQuestion extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;

    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;

    @Column(name = "focus_id", updatable = false)
    private UUID focusId;

    @Column(name = "root_domain", nullable = false, updatable = false, length = 60)
    private String rootDomain;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "question_code", nullable = false, length = 120)
    private String questionCode;

    @Column(name = "workflow_safe_label", length = 120)
    private String workflowSafeLabel;

    @Column(name = "interrupted_question_code", length = 120)
    private String interruptedQuestionCode;

    @Column(name = "deferred_workflow_id")
    private UUID deferredWorkflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationPendingQuestionStatus status;

    @Column(nullable = false)
    private long revision;

    @Column(name = "inbound_idempotency_hmac", nullable = false, length = 64)
    private String inboundIdempotencyHmac;

    @Column(nullable = false)
    private Instant expiresAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConversationPendingQuestion() {
    }

    private ConversationPendingQuestion(ConversationScopeKey scope, WorkspaceChannel channel,
                                        UUID focusId, String rootDomain, UUID workflowId,
                                        String questionCode, String workflowSafeLabel,
                                        String inboundHmac,
                                        Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.conversationScopeDigest = scope.digest();
        this.scopeKeyVersion = scope.keyVersion();
        this.focusId = focusId;
        this.rootDomain = requiredCode(rootDomain, "root domain", 60, false);
        this.workflowId = Objects.requireNonNull(workflowId, "workflow id");
        this.questionCode = requiredCode(questionCode, "question code", 120, true);
        this.workflowSafeLabel = optionalSafeLabel(workflowSafeLabel);
        this.status = ConversationPendingQuestionStatus.PENDING;
        this.revision = 1;
        this.inboundIdempotencyHmac = requiredHmac(inboundHmac);
        if (!Objects.requireNonNull(expiresAt, "expires at").isAfter(now)) {
            throw new IllegalArgumentException("pending question expiry must be in the future");
        }
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static ConversationPendingQuestion pending(
            ConversationScopeKey scope, WorkspaceChannel channel, UUID focusId,
            String rootDomain, UUID workflowId, String questionCode,
            String inboundHmac, Instant expiresAt, Instant now) {
        return pending(scope, channel, focusId, rootDomain, workflowId, questionCode, null,
                inboundHmac, expiresAt, now);
    }

    public static ConversationPendingQuestion pending(
            ConversationScopeKey scope, WorkspaceChannel channel, UUID focusId,
            String rootDomain, UUID workflowId, String questionCode, String workflowSafeLabel,
            String inboundHmac, Instant expiresAt, Instant now) {
        return new ConversationPendingQuestion(Objects.requireNonNull(scope, "scope"), channel,
                focusId, rootDomain, workflowId, questionCode, workflowSafeLabel, inboundHmac,
                expiresAt, now);
    }

    /** Returns false for the same inbound so webhook replay cannot advance the question revision. */
    public boolean ask(String code, String inboundHmac, Instant now) {
        requirePending();
        String validatedHmac = requiredHmac(inboundHmac);
        if (inboundIdempotencyHmac.equals(validatedHmac)) {
            return false;
        }
        if (interruptedQuestionCode != null
                && !code.equals("conversation.context-target")
                && !code.equals("conversation.new-operation-content")) {
            throw new IllegalStateException(
                    "an interrupted question must be resumed or completed explicitly");
        }
        questionCode = requiredCode(code, "question code", 120, true);
        inboundIdempotencyHmac = validatedHmac;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public boolean beginContextChoice(String inboundHmac, Instant now) {
        return beginContextChoice(inboundHmac, null, now);
    }

    public boolean beginContextChoice(
            String inboundHmac, UUID deferredWorkflowId, Instant now) {
        requirePending();
        if (questionCode.equals("conversation.context-target")) return false;
        if (questionCode.equals("conversation.new-operation-content")) {
            throw new IllegalStateException("new operation content is already pending");
        }
        interruptedQuestionCode = questionCode;
        this.deferredWorkflowId = deferredWorkflowId;
        return changeQuestion("conversation.context-target", inboundHmac, now);
    }

    public boolean requestNewOperationContent(String inboundHmac, Instant now) {
        requireQuestion("conversation.context-target");
        return changeQuestion("conversation.new-operation-content", inboundHmac, now);
    }

    public boolean resumeInterruptedQuestion(String inboundHmac, Instant now) {
        requirePending();
        if (!questionCode.equals("conversation.context-target")
                && !questionCode.equals("conversation.new-operation-content")) {
            throw new IllegalStateException("context choice is not pending");
        }
        if (interruptedQuestionCode == null) {
            throw new IllegalStateException("interrupted question is unavailable");
        }
        String restored = interruptedQuestionCode;
        interruptedQuestionCode = null;
        deferredWorkflowId = null;
        return changeQuestion(restored, inboundHmac, now);
    }

    public boolean completeDeferredNewOperation(
            UUID expectedDeferredWorkflowId, String inboundHmac, Instant now) {
        requireQuestion("conversation.context-target");
        if (deferredWorkflowId == null
                || !deferredWorkflowId.equals(expectedDeferredWorkflowId)) {
            throw new IllegalStateException("deferred operation does not match context choice");
        }
        String validatedHmac = requiredHmac(inboundHmac);
        if (inboundIdempotencyHmac.equals(validatedHmac)) return false;
        inboundIdempotencyHmac = validatedHmac;
        interruptedQuestionCode = null;
        deferredWorkflowId = null;
        answer(now);
        return true;
    }

    public boolean completeNewOperationContent(String inboundHmac, Instant now) {
        requireQuestion("conversation.new-operation-content");
        String validatedHmac = requiredHmac(inboundHmac);
        if (inboundIdempotencyHmac.equals(validatedHmac)) return false;
        inboundIdempotencyHmac = validatedHmac;
        answer(now);
        return true;
    }

    private boolean changeQuestion(String code, String inboundHmac, Instant now) {
        String validatedHmac = requiredHmac(inboundHmac);
        if (inboundIdempotencyHmac.equals(validatedHmac)) return false;
        questionCode = requiredCode(code, "question code", 120, true);
        inboundIdempotencyHmac = validatedHmac;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    private void requireQuestion(String expected) {
        requirePending();
        if (!questionCode.equals(expected)) {
            throw new IllegalStateException("pending question does not match context transition");
        }
    }

    public void expire(Instant now) {
        if (status == ConversationPendingQuestionStatus.PENDING) {
            status = ConversationPendingQuestionStatus.EXPIRED;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    public void answer(Instant now) {
        requirePending();
        status = ConversationPendingQuestionStatus.ANSWERED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void cancel(Instant now) {
        requirePending();
        status = ConversationPendingQuestionStatus.CANCELED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    /** Lifecycle close records the exact inbound fence and remains replay-safe. */
    public boolean cancel(String inboundHmac, Instant now) {
        String validatedHmac = requiredHmac(inboundHmac);
        if (inboundIdempotencyHmac.equals(validatedHmac)) return false;
        requirePending();
        inboundIdempotencyHmac = validatedHmac;
        status = ConversationPendingQuestionStatus.CANCELED;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public void suspend(Instant now) {
        requirePending();
        status = ConversationPendingQuestionStatus.SUSPENDED;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void resume(Instant now) {
        if (status != ConversationPendingQuestionStatus.SUSPENDED) {
            throw new IllegalStateException("pending question is not suspended");
        }
        status = ConversationPendingQuestionStatus.PENDING;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean expireIfDue(Instant now) {
        if (status == ConversationPendingQuestionStatus.PENDING && !expiresAt.isAfter(now)) {
            expire(now);
            return true;
        }
        return status == ConversationPendingQuestionStatus.EXPIRED;
    }

    private void requirePending() {
        if (status != ConversationPendingQuestionStatus.PENDING) {
            throw new IllegalStateException("pending question is no longer available");
        }
    }

    private static String requiredCode(String value, String label, int max, boolean allowDot) {
        String pattern = allowDot ? "[a-z0-9][a-z0-9._-]*" : "[a-z0-9][a-z0-9_-]*";
        if (value == null || value.length() > max || !value.matches(pattern)) {
            throw new IllegalArgumentException(label + " must be a stable code");
        }
        return value;
    }

    private static String requiredHmac(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inbound idempotency hmac must be a SHA-256 hex value");
        }
        return value;
    }

    private static String optionalSafeLabel(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 120
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("workflow safe label is invalid");
        }
        return normalized;
    }

    public UUID getId() { return id; }
    public WorkspaceChannel getChannel() { return channel; }
    public String getConversationScopeDigest() { return conversationScopeDigest; }
    public Integer getScopeKeyVersion() { return scopeKeyVersion; }
    public UUID getFocusId() { return focusId; }
    public String getRootDomain() { return rootDomain; }
    public UUID getWorkflowId() { return workflowId; }
    public String getQuestionCode() { return questionCode; }
    public String getWorkflowSafeLabel() { return workflowSafeLabel; }
    public String getInterruptedQuestionCode() { return interruptedQuestionCode; }
    public UUID getDeferredWorkflowId() { return deferredWorkflowId; }
    public ConversationPendingQuestionStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public String getInboundIdempotencyHmac() { return inboundIdempotencyHmac; }
    public Instant getExpiresAt() { return expiresAt; }
}

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

/** Actor-private repair workflow; intentionally contains no conversation text or generic payload. */
@Entity
@Table(name = "conversation_repair_draft")
public class ConversationRepairDraft extends WorkspaceOwnedEntity {

    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private ConversationRepairKind repairKind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;
    @Enumerated(EnumType.STRING) @Column(name = "prior_action", nullable = false, length = 40)
    private ConversationRepairPriorAction priorAction;
    @Enumerated(EnumType.STRING) @Column(name = "time_scope", nullable = false, length = 20)
    private ConversationRepairScope timeScope;
    @Enumerated(EnumType.STRING) @Column(name = "repair_aspect", nullable = false, length = 24)
    private ConversationRepairAspect repairAspect;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ConversationRepairDraftStatus status;
    @Column(name = "suspended_question_id") private UUID suspendedQuestionId;
    @Column(nullable = false) private long revision;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ConversationRepairDraft() {
    }

    public static ConversationRepairDraft create(
            ConversationScopeKey scope, WorkspaceChannel channel,
            ConversationRepairKind repairKind, ConversationRepairPriorAction priorAction,
            ConversationRepairScope timeScope, Instant expiresAt, Instant now) {
        return create(scope, channel, repairKind, priorAction, timeScope, null, expiresAt, now);
    }

    public static ConversationRepairDraft create(
            ConversationScopeKey scope, WorkspaceChannel channel,
            ConversationRepairKind repairKind, ConversationRepairPriorAction priorAction,
            ConversationRepairScope timeScope, UUID suspendedQuestionId,
            Instant expiresAt, Instant now) {
        ConversationRepairDraft draft = new ConversationRepairDraft();
        draft.id = UUID.randomUUID();
        draft.repairKind = repairKind;
        draft.channel = channel;
        draft.conversationScopeDigest = scope.digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.priorAction = priorAction;
        draft.timeScope = timeScope;
        draft.repairAspect = ConversationRepairAspect.UNSPECIFIED;
        draft.status = ConversationRepairDraftStatus.PENDING;
        draft.suspendedQuestionId = suspendedQuestionId;
        draft.revision = 1;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void complete(Instant now) {
        requirePending(now);
        status = ConversationRepairDraftStatus.COMPLETED;
        touch(now);
    }

    public void cancel(Instant now) {
        requirePending(now);
        status = ConversationRepairDraftStatus.CANCELED;
        touch(now);
    }

    public boolean expireIfDue(Instant now) {
        if (status == ConversationRepairDraftStatus.PENDING && !expiresAt.isAfter(now)) {
            status = ConversationRepairDraftStatus.EXPIRED;
            touch(now);
            return true;
        }
        return false;
    }

    public void refine(ConversationRepairAspect aspect, Instant now) {
        requirePending(now);
        if (aspect == null || aspect == ConversationRepairAspect.UNSPECIFIED) {
            throw new IllegalArgumentException("repair aspect is required");
        }
        if (repairAspect != aspect) {
            repairAspect = aspect;
            touch(now);
        }
    }

    private void requirePending(Instant now) {
        if (status != ConversationRepairDraftStatus.PENDING || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("conversation repair draft is unavailable");
        }
    }

    private void touch(Instant now) {
        revision++;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public ConversationRepairKind getRepairKind() { return repairKind; }
    public ConversationRepairPriorAction getPriorAction() { return priorAction; }
    public ConversationRepairScope getTimeScope() { return timeScope; }
    public ConversationRepairAspect getRepairAspect() { return repairAspect; }
    public ConversationRepairDraftStatus getStatus() { return status; }
    public UUID getSuspendedQuestionId() { return suspendedQuestionId; }
    public long getRevision() { return revision; }
}

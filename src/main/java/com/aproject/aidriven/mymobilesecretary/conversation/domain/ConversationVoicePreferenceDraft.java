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

/** Minimal typed state for a multi-turn conversational name preference change. */
@Entity
@Table(name = "conversation_voice_preference_draft")
public class ConversationVoicePreferenceDraft extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, length = 64)
    private String conversationScopeDigest;

    @Column(name = "scope_key_version", nullable = false)
    private int scopeKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConversationVoicePreferenceTarget target;

    @Column(name = "assistant_self_name", length = 96)
    private String assistantSelfName;

    @Column(name = "user_address", length = 96)
    private String userAddress;

    @Column(name = "suspended_question_id")
    private UUID suspendedQuestionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationVoicePreferenceDraftStatus status;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false)
    private Instant expiresAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConversationVoicePreferenceDraft() {
    }

    public static ConversationVoicePreferenceDraft start(
            ConversationScopeKey scope, WorkspaceChannel channel, UUID suspendedQuestionId,
            Instant expiresAt, Instant now) {
        if (!Objects.requireNonNull(expiresAt, "expires at").isAfter(now)) {
            throw new IllegalArgumentException("voice preference draft expiry must be in the future");
        }
        ConversationVoicePreferenceDraft draft = new ConversationVoicePreferenceDraft();
        draft.id = UUID.randomUUID();
        draft.channel = Objects.requireNonNull(channel, "channel");
        draft.conversationScopeDigest = Objects.requireNonNull(scope, "scope").digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.target = ConversationVoicePreferenceTarget.UNDECIDED;
        draft.suspendedQuestionId = suspendedQuestionId;
        draft.status = ConversationVoicePreferenceDraftStatus.PENDING;
        draft.revision = 1;
        draft.expiresAt = expiresAt;
        draft.createdAt = Objects.requireNonNull(now, "now");
        draft.updatedAt = now;
        return draft;
    }

    public void choose(ConversationVoicePreferenceTarget value, Instant now) {
        requirePending();
        if (value == null || value == ConversationVoicePreferenceTarget.UNDECIDED) {
            throw new IllegalArgumentException("voice preference target must be selected");
        }
        target = value;
        changed(now);
    }

    public void answerAssistantName(String value, Instant now) {
        requirePending();
        assistantSelfName = ConversationVoiceProfile.normalizeDisplayValue(
                value, "assistant self name");
        changed(now);
    }

    public void answerUserAddress(String value, Instant now) {
        requirePending();
        userAddress = ConversationVoiceProfile.normalizeDisplayValue(value, "user address");
        changed(now);
    }

    public void answerBoth(String assistantName, String address, Instant now) {
        requirePending();
        assistantSelfName = ConversationVoiceProfile.normalizeDisplayValue(
                assistantName, "assistant self name");
        userAddress = ConversationVoiceProfile.normalizeDisplayValue(address, "user address");
        target = ConversationVoicePreferenceTarget.BOTH;
        changed(now);
    }

    public boolean completeIfReady(Instant now) {
        requirePending();
        boolean ready = switch (target) {
            case ASSISTANT_NAME -> assistantSelfName != null;
            case USER_ADDRESS -> userAddress != null;
            case BOTH -> assistantSelfName != null && userAddress != null;
            case UNDECIDED -> false;
        };
        if (ready) {
            status = ConversationVoicePreferenceDraftStatus.COMPLETED;
            changed(now);
        }
        return ready;
    }

    public void cancel(Instant now) {
        requirePending();
        status = ConversationVoicePreferenceDraftStatus.CANCELED;
        changed(now);
    }

    public boolean expireIfDue(Instant now) {
        if (status == ConversationVoicePreferenceDraftStatus.PENDING && !expiresAt.isAfter(now)) {
            status = ConversationVoicePreferenceDraftStatus.EXPIRED;
            changed(now);
            return true;
        }
        return status == ConversationVoicePreferenceDraftStatus.EXPIRED;
    }

    private void requirePending() {
        if (status != ConversationVoicePreferenceDraftStatus.PENDING) {
            throw new IllegalStateException("voice preference draft is no longer pending");
        }
    }

    private void changed(Instant now) {
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() { return id; }
    public WorkspaceChannel getChannel() { return channel; }
    public String getConversationScopeDigest() { return conversationScopeDigest; }
    public int getScopeKeyVersion() { return scopeKeyVersion; }
    public ConversationVoicePreferenceTarget getTarget() { return target; }
    public String getAssistantSelfName() { return assistantSelfName; }
    public String getUserAddress() { return userAddress; }
    public UUID getSuspendedQuestionId() { return suspendedQuestionId; }
    public ConversationVoicePreferenceDraftStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public Instant getExpiresAt() { return expiresAt; }
}

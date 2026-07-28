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

/** Monotonic ordering fence for one conversation scope; it deliberately stores no active target. */
@Entity
@Table(name = "conversation_focus_head")
public class ConversationFocusHead extends WorkspaceOwnedEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40) private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64) private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false) private Integer scopeKeyVersion;
    @Column(nullable = false) private long revision;
    @Version private Long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected ConversationFocusHead() { }
    private ConversationFocusHead(ConversationScopeKey scope, WorkspaceChannel channel, Instant now) { id=UUID.randomUUID(); conversationScopeDigest=scope.digest(); scopeKeyVersion=scope.keyVersion(); this.channel=channel; createdAt=now; updatedAt=now; }
    public static ConversationFocusHead create(ConversationScopeKey scope, WorkspaceChannel channel, Instant now) { return new ConversationFocusHead(scope, channel, now); }
    public long advance(long expectedRevision, Instant now) { if (revision != expectedRevision) throw new IllegalStateException("conversation focus revision is stale"); revision++; updatedAt=now; return revision; }
    public long getRevision() { return revision; } public UUID getId() { return id; } public String getConversationScopeDigest() { return conversationScopeDigest; } public WorkspaceChannel getChannel() { return channel; }
}

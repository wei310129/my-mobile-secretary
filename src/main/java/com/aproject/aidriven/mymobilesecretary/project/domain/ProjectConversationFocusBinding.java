package com.aproject.aidriven.mymobilesecretary.project.domain;

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

/** Real-FK ownership link between one conversation focus and one Project. */
@Entity
@Table(name = "project_conversation_focus_binding")
public class ProjectConversationFocusBinding extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(name = "conversation_focus_id", nullable = false, updatable = false)
    private UUID conversationFocusId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;

    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectConversationFocusBinding() {
    }

    private ProjectConversationFocusBinding(UUID conversationFocusId, UUID projectId,
                                            WorkspaceChannel channel,
                                            String conversationScopeDigest, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.conversationFocusId =
                Objects.requireNonNull(conversationFocusId, "conversation focus id is required");
        this.projectId = Objects.requireNonNull(projectId, "project id is required");
        this.channel = Objects.requireNonNull(channel, "channel is required");
        if (conversationScopeDigest == null
                || !conversationScopeDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("conversation scope digest is invalid");
        }
        this.conversationScopeDigest = conversationScopeDigest;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static ProjectConversationFocusBinding create(
            UUID conversationFocusId, UUID projectId, WorkspaceChannel channel,
            String conversationScopeDigest, Instant createdAt) {
        return new ProjectConversationFocusBinding(
                conversationFocusId, projectId, channel, conversationScopeDigest, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationFocusId() {
        return conversationFocusId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public WorkspaceChannel getChannel() {
        return channel;
    }

    public String getConversationScopeDigest() {
        return conversationScopeDigest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

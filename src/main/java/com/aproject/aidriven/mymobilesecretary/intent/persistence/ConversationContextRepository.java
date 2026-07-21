package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationContextRepository extends JpaRepository<ConversationContext, Integer> {

    Optional<ConversationContext> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String conversationScopeDigest);

    Optional<ConversationContext> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusId(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String conversationScopeDigest,
            UUID conversationFocusId);

    Optional<ConversationContext> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String conversationScopeDigest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationContext> findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String conversationScopeDigest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationContext> findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndConversationFocusIdIsNull(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String conversationScopeDigest);
}

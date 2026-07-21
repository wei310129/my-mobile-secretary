package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationAsyncWork;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationAsyncWorkRepository extends JpaRepository<ConversationAsyncWork, UUID> {
    Optional<ConversationAsyncWork> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest, String inboundHmac);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationAsyncWork> findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);
}

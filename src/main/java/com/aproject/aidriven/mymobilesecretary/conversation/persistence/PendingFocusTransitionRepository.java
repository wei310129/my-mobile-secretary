package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PendingFocusTransition;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PendingFocusTransitionStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PendingFocusTransitionRepository extends JpaRepository<PendingFocusTransition, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingFocusTransition> findWithLockByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
            UUID id, UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingFocusTransition> findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
            PendingFocusTransitionStatus status);
}

package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusHead;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationFocusHeadRepository extends JpaRepository<ConversationFocusHead, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationFocusHead> findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest);
}

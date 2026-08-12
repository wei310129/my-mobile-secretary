package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraftStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationRepairDraftRepository
        extends JpaRepository<ConversationRepairDraft, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationRepairDraft>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel,
                    String scopeDigest, ConversationRepairDraftStatus status);
}

package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PublicPlaceLookupDraftRepository
        extends JpaRepository<PublicPlaceLookupDraft, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PublicPlaceLookupDraft>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel,
                    String scopeDigest, PublicPlaceLookupDraftStatus status);
}

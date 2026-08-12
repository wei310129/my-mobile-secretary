package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RestaurantBookingDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RestaurantBookingDraftStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RestaurantBookingDraftRepository
        extends JpaRepository<RestaurantBookingDraft, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RestaurantBookingDraft>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String scopeDigest,
                    RestaurantBookingDraftStatus status);
}

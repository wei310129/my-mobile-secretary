package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraftStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePlaceCreationDraftRepository
        extends JpaRepository<RoutePlaceCreationDraft, UUID> {

    Optional<RoutePlaceCreationDraft>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId,
                    UUID actorId,
                    WorkspaceChannel channel,
                    String scopeDigest,
                    RoutePlaceCreationDraftStatus status);

    boolean existsByWorkspaceIdAndCreatedByUserIdAndParentCalendarDraftIdAndStatus(
            UUID workspaceId,
            UUID actorId,
            UUID parentCalendarDraftId,
            RoutePlaceCreationDraftStatus status);
}

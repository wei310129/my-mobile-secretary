package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FocusTransitionRepository extends JpaRepository<FocusTransition, UUID> {
    Optional<FocusTransition> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest, String inboundHmac);
}

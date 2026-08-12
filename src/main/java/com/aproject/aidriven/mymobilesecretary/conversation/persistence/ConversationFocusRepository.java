package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationFocusRepository extends JpaRepository<ConversationFocus, UUID> {
    Optional<ConversationFocus> findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
            com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus status);

    Optional<ConversationFocus> findByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
            UUID id, UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest);

    Optional<ConversationFocus>
            findFirstByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndRootDomainAndRoutingKeyAndStatusOrderByUpdatedAtDesc(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
                    String rootDomain, String routingKey,
                    com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus status);

    Optional<ConversationFocus>
            findFirstByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndRootDomainAndWorkflowIdAndStatusOrderByUpdatedAtDesc(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
                    String rootDomain, UUID workflowId,
                    com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus status);

    List<ConversationFocus>
            findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusInOrderByCreatedAtAsc(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
                    Collection<ConversationFocusStatus> statuses);
}

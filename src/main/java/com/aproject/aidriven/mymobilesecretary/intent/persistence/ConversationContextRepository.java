package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationContextRepository extends JpaRepository<ConversationContext, Integer> {

    Optional<ConversationContext> findByWorkspaceIdAndCreatedByUserIdAndChannel(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel);
}

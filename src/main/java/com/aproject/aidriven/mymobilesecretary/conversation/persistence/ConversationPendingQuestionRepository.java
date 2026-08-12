package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationPendingQuestionRepository
        extends JpaRepository<ConversationPendingQuestion, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationPendingQuestion> findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationPendingQuestion>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String scopeDigest,
                    ConversationPendingQuestionStatus status);

    Optional<ConversationPendingQuestion>
            findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String scopeDigest,
                    ConversationPendingQuestionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationPendingQuestion>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndWorkflowIdAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String scopeDigest,
                    UUID workflowId, ConversationPendingQuestionStatus status);
}

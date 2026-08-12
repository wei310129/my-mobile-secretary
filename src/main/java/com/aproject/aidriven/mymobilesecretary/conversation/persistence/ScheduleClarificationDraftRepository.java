package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraftStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ScheduleClarificationDraftRepository extends JpaRepository<ScheduleClarificationDraft, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScheduleClarificationDraft> findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndCapabilityAndStatus(
            UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
            ScheduleClarificationCapability capability, ScheduleClarificationDraftStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ScheduleClarificationDraft>
            findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusOrderByCreatedAtAsc(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String digest,
                    ScheduleClarificationDraftStatus status);
}

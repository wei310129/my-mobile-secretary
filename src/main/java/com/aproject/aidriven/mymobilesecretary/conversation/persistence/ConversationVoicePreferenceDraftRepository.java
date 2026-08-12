package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraftStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationVoicePreferenceDraftRepository
        extends JpaRepository<ConversationVoicePreferenceDraft, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationVoicePreferenceDraft>
            findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                    UUID workspaceId, UUID actorId, WorkspaceChannel channel, String scopeDigest,
                    ConversationVoicePreferenceDraftStatus status);
}

package com.aproject.aidriven.mymobilesecretary.conversation.persistence;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoiceProfile;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationVoiceProfileRepository
        extends JpaRepository<ConversationVoiceProfile, Long> {

    Optional<ConversationVoiceProfile> findByWorkspaceIdAndCreatedByUserId(
            UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationVoiceProfile> findWithLockByWorkspaceIdAndCreatedByUserId(
            UUID workspaceId, UUID actorId);
}

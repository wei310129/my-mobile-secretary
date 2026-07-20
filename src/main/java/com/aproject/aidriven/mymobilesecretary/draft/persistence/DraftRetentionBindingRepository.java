package com.aproject.aidriven.mymobilesecretary.draft.persistence;

import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRetentionBindingRepository extends JpaRepository<DraftRetentionBinding, Long> {
    Optional<DraftRetentionBinding> findByCreatedByUserIdAndDraftTypeAndDraftId(
            UUID actorId, DraftType type, Long draftId);
    Optional<DraftRetentionBinding> findFirstByCreatedByUserIdOrderByUpdatedAtDesc(UUID actorId);
    List<DraftRetentionBinding> findByCreatedByUserId(UUID actorId);
    List<DraftRetentionBinding> findByCreatedByUserIdAndNotifiedAtIsNullAndRemindAtLessThanEqual(
            UUID actorId, Instant now);
    List<DraftRetentionBinding> findByCreatedByUserIdAndExpiresAtLessThanEqual(
            UUID actorId, Instant now);
}

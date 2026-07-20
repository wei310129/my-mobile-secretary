package com.aproject.aidriven.mymobilesecretary.draft.persistence;

import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionPreference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRetentionPreferenceRepository
        extends JpaRepository<DraftRetentionPreference, Long> {
    Optional<DraftRetentionPreference> findByCreatedByUserId(UUID actorId);
}

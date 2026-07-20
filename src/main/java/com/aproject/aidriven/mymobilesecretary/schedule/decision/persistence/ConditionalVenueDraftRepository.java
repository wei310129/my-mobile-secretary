package com.aproject.aidriven.mymobilesecretary.schedule.decision.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.decision.domain.ConditionalVenueDraft;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConditionalVenueDraftRepository
        extends JpaRepository<ConditionalVenueDraft, Long> {

    Optional<ConditionalVenueDraft>
            findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                    UUID actorId, ConditionalVenueDraft.Status status);
}

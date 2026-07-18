package com.aproject.aidriven.mymobilesecretary.event.persistence;

import com.aproject.aidriven.mymobilesecretary.event.domain.EventIntakeDraft;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventIntakeDraftRepository extends JpaRepository<EventIntakeDraft, Long> {

    Optional<EventIntakeDraft> findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID actorId, EventIntakeDraft.Status status, Instant now);
}

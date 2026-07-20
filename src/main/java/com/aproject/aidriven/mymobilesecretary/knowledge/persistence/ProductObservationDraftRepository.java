package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductObservationDraftRepository
        extends JpaRepository<ProductObservationDraft, Long> {
    Optional<ProductObservationDraft>
            findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        UUID actorId, Status status, Instant now);

    Optional<ProductObservationDraft>
            findFirstByCreatedByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID actorId, Instant now);

    List<ProductObservationDraft> findByCreatedByUserIdAndStatusAndExpiresAtBetween(
            UUID actorId, Status status, Instant start, Instant end);

    long deleteByCreatedByUserIdAndStatusAndExpiresAtLessThanEqual(
            UUID actorId, Status status, Instant cutoff);
}

package com.aproject.aidriven.mymobilesecretary.payment.persistence;

import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransferDraftRepository extends JpaRepository<BankTransferDraft, Long> {
    Optional<BankTransferDraft>
            findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID actorId, Status status, Instant now);

    List<BankTransferDraft> findByCreatedByUserIdAndStatusAndExpiresAtBetween(
            UUID actorId, Status status, Instant start, Instant end);

    long deleteByCreatedByUserIdAndStatusAndExpiresAtLessThanEqual(
            UUID actorId, Status status, Instant cutoff);
}

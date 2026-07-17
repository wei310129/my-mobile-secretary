package com.aproject.aidriven.mymobilesecretary.family.persistence;

import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyNoticeDraft;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyNoticeDraft.Status;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyNoticeDraftRepository extends JpaRepository<FamilyNoticeDraft, Long> {

    Optional<FamilyNoticeDraft>
            findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID actorId, Status status, Instant now);
}

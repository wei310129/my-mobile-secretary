package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.intent.domain.SchoolTransportDraft;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolTransportDraftRepository extends JpaRepository<SchoolTransportDraft, Long> {

    Optional<SchoolTransportDraft>
            findFirstByWorkspaceIdAndCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID workspaceId, UUID actorId, SchoolTransportDraft.Status status, Instant now);
}

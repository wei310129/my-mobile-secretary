package com.aproject.aidriven.mymobilesecretary.safety.persistence;

import com.aproject.aidriven.mymobilesecretary.safety.domain.WorkSchoolSuspensionDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSchoolSuspensionDraftRepository
        extends JpaRepository<WorkSchoolSuspensionDraft, Long> {

    Optional<WorkSchoolSuspensionDraft>
            findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                    UUID actorId, WorkSchoolSuspensionDraft.Status status);

    List<WorkSchoolSuspensionDraft> findByStatusAndVerifyAfterLessThanEqualOrderByVerifyAfterAsc(
            WorkSchoolSuspensionDraft.Status status, Instant now);
}

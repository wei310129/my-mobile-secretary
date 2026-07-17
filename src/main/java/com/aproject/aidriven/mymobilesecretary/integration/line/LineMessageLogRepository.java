package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** LineMessageLog 資料存取。 */
public interface LineMessageLogRepository extends JpaRepository<LineMessageLog, Long> {

    List<LineMessageLog> findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
            UUID workspaceId, UUID actorId, Pageable pageable);

    Optional<LineMessageLog> findByIdAndWorkspaceIdAndCreatedByUserId(
            Long id, UUID workspaceId, UUID actorId);

    List<LineMessageLog> findAllByDirectionAndWorkspaceIdAndCreatedByUserIdAndIdGreaterThanOrderByIdAsc(
            LineMessageLog.Direction direction,
            UUID workspaceId,
            UUID actorId,
            Long afterId,
            Pageable pageable);

    long deleteByWorkspaceIdAndPinnedFalseAndExpiresAtBefore(UUID workspaceId, Instant cutoff);
}

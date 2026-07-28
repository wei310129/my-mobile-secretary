package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarOnlineAccessLinkRepository
        extends JpaRepository<CalendarOnlineAccessLinkEntity, UUID> {

    Optional<CalendarOnlineAccessLinkEntity>
            findByPlanIdAndActivityIdIsNullAndNodeIdIsNullAndWorkspaceIdAndCreatedByUserId(
                    UUID planId, UUID workspaceId, UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_online_access_link (
                id, plan_id, normalized_uri, safe_host, label,
                created_at, updated_at, workspace_id, created_by_user_id)
            VALUES (
                :id, :planId, :normalizedUri, :safeHost, :label,
                :now, :now, :workspaceId, :sourceOwnerId)
            """, nativeQuery = true)
    int insertPlanLink(
            @Param("id") UUID id,
            @Param("planId") UUID planId,
            @Param("normalizedUri") String normalizedUri,
            @Param("safeHost") String safeHost,
            @Param("label") String label,
            @Param("now") Instant now,
            @Param("workspaceId") UUID workspaceId,
            @Param("sourceOwnerId") UUID sourceOwnerId);
}

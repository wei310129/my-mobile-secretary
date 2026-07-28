package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarTimeNodeRepository extends JpaRepository<CalendarTimeNodeEntity, UUID> {

    List<CalendarTimeNodeEntity>
            findAllByPlanIdAndWorkspaceIdAndCreatedByUserIdOrderByCreatedAt(
                    UUID planId, UUID workspaceId, UUID actorId);

    Optional<CalendarTimeNodeEntity>
            findByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                    UUID planId, String nodeKey, UUID workspaceId, UUID actorId);

    Optional<CalendarTimeNodeEntity> findByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CalendarTimeNodeEntity>
            findWithLockByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                    UUID planId, String nodeKey, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CalendarTimeNodeEntity>
            findWithLockByIdAndPlanIdAndWorkspaceIdAndCreatedByUserId(
                    UUID id, UUID planId, UUID workspaceId, UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_time_node (
                id, plan_id, node_key, label, expression_kind,
                absolute_time, resolved_time, criticality, adjustability,
                created_at, updated_at, workspace_id, created_by_user_id)
            VALUES (
                :id, :planId, :nodeKey, :label, 'ABSOLUTE',
                :time, :time, 'NORMAL', 'LOCKED',
                :now, :now, :workspaceId, :sourceOwnerId)
            """, nativeQuery = true)
    int insertAbsolute(
            @Param("id") UUID id,
            @Param("planId") UUID planId,
            @Param("nodeKey") String nodeKey,
            @Param("label") String label,
            @Param("time") Instant time,
            @Param("now") Instant now,
            @Param("workspaceId") UUID workspaceId,
            @Param("sourceOwnerId") UUID sourceOwnerId);
}

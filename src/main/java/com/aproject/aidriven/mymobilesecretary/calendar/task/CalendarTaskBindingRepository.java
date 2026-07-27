package com.aproject.aidriven.mymobilesecretary.calendar.task;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarTaskBindingRepository
        extends JpaRepository<CalendarTaskBindingEntity, UUID> {

    Optional<CalendarTaskBindingEntity>
            findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                    String requestHash, UUID workspaceId, UUID actorId);

    Optional<CalendarTaskBindingEntity> findByTaskIdAndWorkspaceIdAndCreatedByUserId(
            Long taskId, UUID workspaceId, UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO calendar_task_binding (
                        id, task_id, target_kind, plan_id, activity_id, node_id,
                        creation_request_hash, creation_payload_hash, created_at,
                        workspace_id, created_by_user_id)
                    VALUES (
                        :id, :taskId, :targetKind, :planId, :activityId, :nodeId,
                        :requestHash, :payloadHash, :createdAt, :workspaceId, :actorId)
                    ON CONFLICT (workspace_id, created_by_user_id, creation_request_hash)
                    DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("taskId") Long taskId,
            @Param("targetKind") String targetKind,
            @Param("planId") UUID planId,
            @Param("activityId") UUID activityId,
            @Param("nodeId") UUID nodeId,
            @Param("requestHash") String requestHash,
            @Param("payloadHash") String payloadHash,
            @Param("createdAt") Instant createdAt,
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId);
}

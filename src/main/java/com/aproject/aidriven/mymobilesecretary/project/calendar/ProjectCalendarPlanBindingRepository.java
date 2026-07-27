package com.aproject.aidriven.mymobilesecretary.project.calendar;

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

public interface ProjectCalendarPlanBindingRepository
        extends JpaRepository<ProjectCalendarPlanBinding, UUID> {

    Optional<ProjectCalendarPlanBinding>
            findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                    String requestHash, UUID workspaceId, UUID actorId);

    Optional<ProjectCalendarPlanBinding>
            findByPlanIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                    UUID planId,
                    ProjectCalendarBindingStatus status,
                    UUID workspaceId,
                    UUID actorId);

    List<ProjectCalendarPlanBinding>
            findAllByProjectIdAndWorkspaceIdAndCreatedByUserIdOrderByUpdatedAtDesc(
                    UUID projectId, UUID workspaceId, UUID actorId);

    Optional<ProjectCalendarPlanBinding>
            findByIdAndProjectIdAndWorkspaceIdAndCreatedByUserId(
                    UUID id, UUID projectId, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectCalendarPlanBinding>
            findWithLockByIdAndProjectIdAndWorkspaceIdAndCreatedByUserId(
                    UUID id, UUID projectId, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectCalendarPlanBinding>
            findWithLockByPlanIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                    UUID planId,
                    ProjectCalendarBindingStatus status,
                    UUID workspaceId,
                    UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO project_calendar_plan_binding (
                id, project_id, calendar_plan_id, status, binding_revision,
                creation_request_hash, creation_payload_hash,
                version, created_at, updated_at,
                workspace_id, created_by_user_id)
            VALUES (
                :id, :projectId, :planId, 'ACTIVE', 1,
                :requestHash, :payloadHash,
                0, :now, :now, :workspaceId, :actorId)
            ON CONFLICT (
                workspace_id, created_by_user_id, creation_request_hash)
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("planId") UUID planId,
            @Param("requestHash") String requestHash,
            @Param("payloadHash") String payloadHash,
            @Param("now") Instant now,
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId);
}

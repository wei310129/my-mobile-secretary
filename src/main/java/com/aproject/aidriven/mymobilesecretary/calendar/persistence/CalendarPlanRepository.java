package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarPlanRepository extends JpaRepository<CalendarPlanEntity, UUID> {

    Optional<CalendarPlanEntity> findByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CalendarPlanEntity> findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    Optional<CalendarPlanEntity> findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
            String requestHash, UUID workspaceId, UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_plan (
                id, title, placement_kind, timed_start, timed_end, zone_id,
                all_day_start, all_day_end_exclusive,
                creation_request_hash, creation_payload_hash, category,
                version, created_at, updated_at, workspace_id, created_by_user_id)
            VALUES (
                :id, :title, :placementKind, :timedStart, :timedEnd, :zoneId,
                :allDayStart, :allDayEndExclusive,
                :requestHash, :payloadHash, :category,
                0, :now, :now, :workspaceId, :actorId)
            ON CONFLICT (
                workspace_id, created_by_user_id, creation_request_hash)
            WHERE creation_request_hash IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("placementKind") String placementKind,
            @Param("timedStart") Instant timedStart,
            @Param("timedEnd") Instant timedEnd,
            @Param("zoneId") String zoneId,
            @Param("allDayStart") java.time.LocalDate allDayStart,
            @Param("allDayEndExclusive") java.time.LocalDate allDayEndExclusive,
            @Param("requestHash") String requestHash,
            @Param("payloadHash") String payloadHash,
            @Param("category") String category,
            @Param("now") Instant now,
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId);
}

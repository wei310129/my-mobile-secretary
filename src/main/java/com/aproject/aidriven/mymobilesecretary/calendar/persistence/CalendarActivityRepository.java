package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarActivityRepository extends JpaRepository<CalendarActivityEntity, UUID> {

    List<CalendarActivityEntity> findAllByPlanIdAndWorkspaceIdAndCreatedByUserIdOrderByCreatedAt(
            UUID planId, UUID workspaceId, UUID actorId);

    Optional<CalendarActivityEntity> findByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);
}

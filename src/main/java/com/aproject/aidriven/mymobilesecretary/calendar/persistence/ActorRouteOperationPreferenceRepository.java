package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.ActorRouteOperationPreference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActorRouteOperationPreferenceRepository
        extends JpaRepository<ActorRouteOperationPreference, Long> {

    Optional<ActorRouteOperationPreference> findByWorkspaceIdAndCreatedByUserId(
            UUID workspaceId, UUID actorId);
}

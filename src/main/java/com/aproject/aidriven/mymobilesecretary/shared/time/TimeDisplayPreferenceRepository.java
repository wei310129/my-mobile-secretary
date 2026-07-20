package com.aproject.aidriven.mymobilesecretary.shared.time;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeDisplayPreferenceRepository
        extends JpaRepository<TimeDisplayPreference, Long> {

    Optional<TimeDisplayPreference> findByWorkspaceIdAndCreatedByUserId(
            UUID workspaceId, UUID createdByUserId);
}

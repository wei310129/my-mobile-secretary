package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.ActorLocationKind;
import com.aproject.aidriven.mymobilesecretary.geo.domain.ActorLocationPreference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActorLocationPreferenceRepository
        extends JpaRepository<ActorLocationPreference, Long> {

    Optional<ActorLocationPreference>
            findByWorkspaceIdAndCreatedByUserIdAndLocationKind(
                    UUID workspaceId, UUID createdByUserId, ActorLocationKind locationKind);
}

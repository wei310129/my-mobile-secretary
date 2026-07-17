package com.aproject.aidriven.mymobilesecretary.travel.persistence;

import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPackingPreferenceRepository
        extends JpaRepository<TravelPackingPreference, Long> {

    Optional<TravelPackingPreference> findByCreatedByUserIdAndNormalizedItem(
            UUID actorId, String normalizedItem);

    List<TravelPackingPreference> findByCreatedByUserIdOrderByItemNameAsc(UUID actorId);
}

package com.aproject.aidriven.mymobilesecretary.venue.persistence;

import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueVisitInformationRepository
        extends JpaRepository<VenueVisitInformation, Long> {
    Optional<VenueVisitInformation>
            findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(UUID actorId, Status status);

    List<VenueVisitInformation> findByCreatedByUserIdAndStatusOrderByUpdatedAtDesc(
            UUID actorId, Status status);
}

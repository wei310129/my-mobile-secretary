package com.aproject.aidriven.mymobilesecretary.travel.persistence;

import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft.Status;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelItineraryDraftRepository extends JpaRepository<TravelItineraryDraft, Long> {

    Optional<TravelItineraryDraft>
            findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID actorId, Status status, Instant now);

    /** Native system-retention path intentionally bypasses Hibernate's tenant predicate. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM travel_itinerary_draft WHERE expires_at <= :cutoff",
            nativeQuery = true)
    int deleteExpiredForSystem(@Param("cutoff") Instant cutoff);
}

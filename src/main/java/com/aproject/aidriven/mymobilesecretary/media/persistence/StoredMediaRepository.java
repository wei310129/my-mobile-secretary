package com.aproject.aidriven.mymobilesecretary.media.persistence;

import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.SourceType;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredMediaRepository extends JpaRepository<StoredMedia, Long> {

    Optional<StoredMedia> findByIdAndCreatedByUserIdAndStatus(
            Long id, UUID actorId, Status status);

    Optional<StoredMedia> findBySourceTypeAndSourceReferenceAndCreatedByUserIdAndStatus(
            SourceType sourceType, String sourceReference, UUID actorId, Status status);

    List<StoredMedia> findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
            UUID actorId, Status status, Pageable pageable);

    @Query("select coalesce(sum(m.sizeBytes), 0) from StoredMedia m "
            + "where m.createdByUserId = :actorId and m.status = :status")
    long sumSizeBytesByActorIdAndStatus(
            @Param("actorId") UUID actorId, @Param("status") Status status);
}

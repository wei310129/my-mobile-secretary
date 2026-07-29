package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObjectAnnotationRepository extends JpaRepository<ObjectAnnotation, Long> {

    java.util.Optional<ObjectAnnotation> findByIdAndWorkspaceIdAndCreatedByUserId(
            Long id, UUID workspaceId, UUID actorId);

    @Query("""
            select annotation from ObjectAnnotation annotation
            where annotation.workspaceId = :workspaceId
              and annotation.createdByUserId = :actorId
              and annotation.archivedAt is null
              and annotation.normalizedSubject like concat(:subjectPrefix, '%')
              and annotation.createdAt >= :createdFrom
              and annotation.createdAt < :createdTo
            order by case when annotation.normalizedSubject = :subjectPrefix then 0 else 1 end,
                     annotation.updatedAt desc, annotation.id desc
            """)
    List<ObjectAnnotation> searchActiveBySubjectPrefix(
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("subjectPrefix") String subjectPrefix,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            Pageable pageable);

    @Query("""
            select annotation from ObjectAnnotation annotation
            where annotation.workspaceId = :workspaceId
              and annotation.createdByUserId = :actorId
              and annotation.archivedAt is null
              and annotation.targetType = :targetType
              and annotation.normalizedSubject like concat(:subjectPrefix, '%')
              and annotation.createdAt >= :createdFrom
              and annotation.createdAt < :createdTo
            order by case when annotation.normalizedSubject = :subjectPrefix then 0 else 1 end,
                     annotation.updatedAt desc, annotation.id desc
            """)
    List<ObjectAnnotation> searchActiveBySubjectPrefixAndTargetType(
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("subjectPrefix") String subjectPrefix,
            @Param("targetType") ObjectAnnotation.TargetType targetType,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            Pageable pageable);
}

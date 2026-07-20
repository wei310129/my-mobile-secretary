package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserKnowledgeFactRepository extends JpaRepository<UserKnowledgeFact, Long> {

    Optional<UserKnowledgeFact> findByWorkspaceIdAndCreatedByUserIdAndCategoryAndNormalizedSubject(
            UUID workspaceId, UUID actorId, Category category, String normalizedSubject);

    List<UserKnowledgeFact> findByWorkspaceIdAndCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(
            UUID workspaceId, UUID actorId, Category category);

    @Query("""
            select fact from UserKnowledgeFact fact
            where fact.workspaceId = :workspaceId
              and fact.createdByUserId = :actorId
              and fact.normalizedSubject like concat(:subjectPrefix, '%')
              and fact.createdAt >= :createdFrom
              and fact.createdAt < :createdTo
            order by case when fact.normalizedSubject = :subjectPrefix then 0 else 1 end,
                     fact.updatedAt desc, fact.id desc
            """)
    List<UserKnowledgeFact> searchBySubjectPrefix(
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("subjectPrefix") String subjectPrefix,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            Pageable pageable);

    @Query("""
            select fact from UserKnowledgeFact fact
            where fact.workspaceId = :workspaceId
              and fact.createdByUserId = :actorId
              and fact.category in :categories
              and fact.normalizedSubject like concat(:subjectPrefix, '%')
              and fact.createdAt >= :createdFrom
              and fact.createdAt < :createdTo
            order by case when fact.normalizedSubject = :subjectPrefix then 0 else 1 end,
                     fact.updatedAt desc, fact.id desc
            """)
    List<UserKnowledgeFact> searchBySubjectPrefixAndCategories(
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("subjectPrefix") String subjectPrefix,
            @Param("categories") Set<Category> categories,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            Pageable pageable);
}

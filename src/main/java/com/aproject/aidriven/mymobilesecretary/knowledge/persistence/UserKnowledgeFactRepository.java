package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserKnowledgeFactRepository extends JpaRepository<UserKnowledgeFact, Long> {

    Optional<UserKnowledgeFact> findByCreatedByUserIdAndCategoryAndNormalizedSubject(
            UUID actorId, Category category, String normalizedSubject);

    List<UserKnowledgeFact> findByCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(
            UUID actorId, Category category);
}

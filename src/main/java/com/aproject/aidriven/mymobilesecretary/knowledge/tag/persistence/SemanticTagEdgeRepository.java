package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticTagEdgeRepository extends JpaRepository<SemanticTagEdge, Long> {
    boolean existsByFromTagIdAndToTagIdAndRelationType(
            Long from, Long to, SemanticTagEdge.RelationType relationType);
}

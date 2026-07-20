package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticTagRepository extends JpaRepository<SemanticTag, Long> {
    Optional<SemanticTag> findByNormalizedNameAndKind(
            String normalizedName, SemanticTag.Kind kind);
    List<SemanticTag> findByNormalizedName(String normalizedName);
}

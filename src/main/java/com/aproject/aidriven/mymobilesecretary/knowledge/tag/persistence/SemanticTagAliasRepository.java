package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagAlias;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticTagAliasRepository extends JpaRepository<SemanticTagAlias, Long> {
    Optional<SemanticTagAlias> findByNormalizedAlias(String normalizedAlias);
    boolean existsByNormalizedAlias(String normalizedAlias);
}

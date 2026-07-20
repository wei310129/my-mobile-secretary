package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagBinding;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticTagBindingRepository extends JpaRepository<SemanticTagBinding, Long> {
    boolean existsByTagIdAndTargetTypeAndTargetId(
            Long tagId, SemanticTagBinding.TargetType targetType, Long targetId);
    List<SemanticTagBinding> findByTagIdIn(Collection<Long> tagIds);
}

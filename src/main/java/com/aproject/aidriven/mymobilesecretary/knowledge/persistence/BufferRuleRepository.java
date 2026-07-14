package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** BufferRule 資料存取。 */
public interface BufferRuleRepository extends JpaRepository<BufferRule, Long> {

    Optional<BufferRule> findByPlaceId(Long placeId);
}

package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** BufferRule 資料存取。 */
public interface BufferRuleRepository extends JpaRepository<BufferRule, Long> {

    Optional<BufferRule> findByPlaceIdAndCreatedByUserId(Long placeId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT rule
            FROM BufferRule rule
            WHERE rule.placeId = :placeId
              AND rule.createdByUserId = :actorId
            """)
    Optional<BufferRule> findForUpdateByPlaceIdAndActorId(
            @Param("placeId") Long placeId, @Param("actorId") UUID actorId);
}

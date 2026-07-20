package com.aproject.aidriven.mymobilesecretary.integration.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from NotificationOutbox n
            where n.targetUserId = :targetUserId
              and n.status = :status and n.availableAt <= :now
            order by n.availableAt, n.id
            """)
    List<NotificationOutbox> findClaimable(
            @Param("targetUserId") UUID targetUserId,
            @Param("status") NotificationOutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from NotificationOutbox n
            where n.targetUserId = :targetUserId
              and n.status = :status and n.claimedUntil <= :now
            order by n.claimedUntil, n.id
            """)
    List<NotificationOutbox> findExpiredClaims(
            @Param("targetUserId") UUID targetUserId,
            @Param("status") NotificationOutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}

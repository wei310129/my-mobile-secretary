package com.aproject.aidriven.mymobilesecretary.integration.notification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from NotificationOutbox n
            where n.status = :status and n.availableAt <= :now
            order by n.availableAt, n.id
            """)
    List<NotificationOutbox> findClaimable(
            @Param("status") NotificationOutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from NotificationOutbox n
            where n.status = :status and n.claimedUntil <= :now
            order by n.claimedUntil, n.id
            """)
    List<NotificationOutbox> findExpiredClaims(
            @Param("status") NotificationOutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}

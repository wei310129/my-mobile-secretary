package com.aproject.aidriven.mymobilesecretary.account.audit;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityAuditRepository extends JpaRepository<SecurityAuditEvent, Long> {
    long deleteByExpiresAtBefore(Instant cutoff);
}

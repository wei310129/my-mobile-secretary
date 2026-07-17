package com.aproject.aidriven.mymobilesecretary.account.audit;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Audit failures never expose request data and never break the user operation being audited. */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final SecurityAuditRepository repository;
    private final SecurityAuditProperties properties;
    private final Clock clock;

    public SecurityAuditService(SecurityAuditRepository repository,
                                SecurityAuditProperties properties,
                                Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSafely(SecurityAuditDraft draft) {
        try {
            Instant now = Instant.now(clock);
            repository.save(SecurityAuditEvent.from(draft, now, now.plus(properties.retention())));
        } catch (Exception exception) {
            log.warn("Security audit write failed [eventType={}]",
                    draft == null ? "unknown" : draft.eventType());
        }
    }

    @Transactional
    public long purgeExpired() {
        return repository.deleteByExpiresAtBefore(Instant.now(clock));
    }
}

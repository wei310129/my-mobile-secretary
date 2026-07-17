package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for privacy-limited intent decision traces. */
public interface IntentDecisionTraceRepository extends JpaRepository<IntentDecisionTrace, Long> {

    Optional<IntentDecisionTrace> findByRequestId(UUID requestId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from IntentDecisionTrace trace
            where trace.summaryExpiresAt <= :now
            """)
    int deleteExpiredSummaries(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntentDecisionTrace trace
            set trace.rawInputEncrypted = null,
                trace.rawOutputEncrypted = null,
                trace.rawCipherKeyId = null,
                trace.rawExpiresAt = null
            where trace.rawExpiresAt <= :now
              and (trace.rawInputEncrypted is not null or trace.rawOutputEncrypted is not null)
            """)
    int clearExpiredRawPayloads(@Param("now") Instant now);
}

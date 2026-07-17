package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the raw (short) and summary (longer) retention windows in one bounded operation. */
@Service
public class IntentTraceRetentionService {

    private final IntentDecisionTraceRepository repository;
    private final Clock clock;

    public IntentTraceRetentionService(IntentDecisionTraceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public PurgeResult purgeExpired() {
        Instant now = Instant.now(clock);
        int summariesDeleted = repository.deleteExpiredSummaries(now);
        int rawPayloadsCleared = repository.clearExpiredRawPayloads(now);
        return new PurgeResult(rawPayloadsCleared, summariesDeleted, now);
    }

    public record PurgeResult(int rawPayloadsCleared, int summariesDeleted, Instant cutoff) {
    }
}

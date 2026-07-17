package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentTraceRetentionService;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies the Flyway schema, Hibernate JSON/bytea mappings and both retention operations. */
class IntentDecisionTraceRepositoryTest extends IntegrationTestBase {

    @Autowired
    private IntentDecisionTraceRepository repository;
    @Autowired
    private IntentTraceRetentionService retentionService;

    @Test
    void clearsExpiredRawPayloadAndDeletesExpiredSummary() {
        Instant now = Instant.now();
        IntentDecisionTrace expiredSummary = trace(
                now.minus(Duration.ofDays(100)), null, now.minus(Duration.ofDays(10)), false);
        IntentDecisionTrace expiredRaw = trace(
                now.minus(Duration.ofDays(10)), now.minusSeconds(1), now.plus(Duration.ofDays(80)), true);
        IntentDecisionTrace freshRaw = trace(
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(6)), now.plus(Duration.ofDays(89)), true);
        repository.saveAndFlush(expiredSummary);
        repository.saveAndFlush(expiredRaw);
        repository.saveAndFlush(freshRaw);

        IntentTraceRetentionService.PurgeResult result = retentionService.purgeExpired();

        assertThat(result.summariesDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(result.rawPayloadsCleared()).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(expiredSummary.getId())).isEmpty();
        IntentDecisionTrace cleared = repository.findById(expiredRaw.getId()).orElseThrow();
        assertThat(cleared.getRawInputEncrypted()).isNull();
        assertThat(cleared.getRawOutputEncrypted()).isNull();
        assertThat(cleared.getRawCipherKeyId()).isNull();
        assertThat(cleared.getRawExpiresAt()).isNull();
        IntentDecisionTrace retained = repository.findById(freshRaw.getId()).orElseThrow();
        assertThat(retained.getRawInputEncrypted()).containsExactly(1, 2, 3);

        repository.deleteAllById(java.util.List.of(expiredRaw.getId(), freshRaw.getId()));
    }

    private static IntentDecisionTrace trace(Instant createdAt, Instant rawExpiresAt,
            Instant summaryExpiresAt, boolean withRaw) {
        IntentDecisionTraceDraft draft = IntentDecisionTraceDraft.builder(UUID.randomUUID(), "TEST")
                .candidate("task.create", 0.9)
                .stageLatency("total", 12)
                .redactedSummary("test trace")
                .build();
        return IntentDecisionTrace.capture(
                draft,
                withRaw ? new byte[] {1, 2, 3} : null,
                withRaw ? new byte[] {4, 5, 6} : null,
                withRaw ? "test-key" : null,
                createdAt,
                rawExpiresAt,
                summaryExpiresAt);
    }
}

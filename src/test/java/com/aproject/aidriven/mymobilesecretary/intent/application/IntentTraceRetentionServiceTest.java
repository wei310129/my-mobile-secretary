package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IntentTraceRetentionServiceTest {

    @Test
    void deletesExpiredSummariesBeforeClearingRemainingRawPayloads() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        IntentDecisionTraceRepository repository = mock(IntentDecisionTraceRepository.class);
        when(repository.deleteExpiredSummaries(now)).thenReturn(2);
        when(repository.clearExpiredRawPayloads(now)).thenReturn(5);
        IntentTraceRetentionService service = new IntentTraceRetentionService(
                repository, Clock.fixed(now, ZoneOffset.UTC));

        IntentTraceRetentionService.PurgeResult result = service.purgeExpired();

        assertThat(result.summariesDeleted()).isEqualTo(2);
        assertThat(result.rawPayloadsCleared()).isEqualTo(5);
        assertThat(result.cutoff()).isEqualTo(now);
        InOrder order = inOrder(repository);
        order.verify(repository).deleteExpiredSummaries(now);
        order.verify(repository).clearExpiredRawPayloads(now);
    }
}

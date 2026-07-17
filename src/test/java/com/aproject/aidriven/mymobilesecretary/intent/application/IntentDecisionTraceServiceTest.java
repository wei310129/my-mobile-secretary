package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntentDecisionTraceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IntentTraceProperties PROPERTIES = new IntentTraceProperties(
            null, "trace-key-v1", Duration.ofDays(7), Duration.ofDays(90));

    @Test
    void encryptsRawDataAndPersistsOnlyBoundedDiagnosticFields() {
        IntentDecisionTraceWriter writer = mock(IntentDecisionTraceWriter.class);
        AesGcmSecretTextCipher cipher = new AesGcmSecretTextCipher(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                "trace-key-v1");
        IntentDecisionTraceService service = new IntentDecisionTraceService(
                writer, cipher, PROPERTIES, CLOCK);
        UUID requestId = UUID.randomUUID();
        IntentDecisionTraceDraft draft = IntentDecisionTraceDraft.builder(requestId, "LINE")
                .versions("router-v1", "prompt-v2", "capability-v3")
                .candidate("task.create", 0.95)
                .shadowRouting(
                        "capability-shadow/v1",
                        "SHADOW",
                        "NONE",
                        "capability-candidates/v1",
                        "a".repeat(64),
                        Map.of(
                                "legacyEstimatedTokens", 250,
                                "candidateEstimatedTokens", 50,
                                "estimatedTokenSavings", 200),
                        List.of("task.open"))
                .selectedCapability("task.create")
                .validationOutcome(IntentDecisionTrace.ValidationOutcome.PASSED)
                .executionOutcome(IntentDecisionTrace.ExecutionOutcome.SUCCEEDED)
                .modelUsage("claude-sonnet", 120, 35)
                .stageLatency("model", 420)
                .rawExchange("raw input", "raw output")
                .redactedSummary("created one task")
                .build();

        assertThat(service.recordSafely(draft)).isTrue();

        ArgumentCaptor<IntentDecisionTrace> traceCaptor =
                ArgumentCaptor.forClass(IntentDecisionTrace.class);
        verify(writer).write(traceCaptor.capture());
        IntentDecisionTrace stored = traceCaptor.getValue();
        assertThat(stored.getRequestId()).isEqualTo(requestId);
        assertThat(stored.getCandidateSummary()).containsEntry("task.create", 0.95);
        assertThat(stored.getShadowRouterVersion()).isEqualTo("capability-shadow/v1");
        assertThat(stored.getShadowDisposition()).isEqualTo("SHADOW");
        assertThat(stored.getShadowFallbackReason()).isEqualTo("NONE");
        assertThat(stored.getShadowPromptVersion()).isEqualTo("capability-candidates/v1");
        assertThat(stored.getShadowPromptHash()).isEqualTo("a".repeat(64));
        assertThat(stored.getShadowTokenEstimate())
                .containsEntry("estimatedTokenSavings", 200);
        assertThat(stored.getShadowContextPlan()).containsExactly("task.open");
        assertThat(stored.getStageLatenciesMs()).containsEntry("model", 420L);
        assertThat(stored.getRawInputEncrypted()).isNotEqualTo("raw input".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.getRawOutputEncrypted()).isNotEqualTo("raw output".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.getRawCipherKeyId()).isEqualTo("trace-key-v1");
        assertThat(stored.getRawExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(stored.getSummaryExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    void localDisabledCipherPersistsSummaryWithoutRawData() {
        IntentDecisionTraceWriter writer = mock(IntentDecisionTraceWriter.class);
        IntentDecisionTraceService service = new IntentDecisionTraceService(
                writer, new NoRawSecretTextCipher(), PROPERTIES, CLOCK);
        IntentDecisionTraceDraft draft = IntentDecisionTraceDraft.builder(UUID.randomUUID(), "REST")
                .rawExchange("raw input", "raw output")
                .redactedSummary("safe summary")
                .build();

        assertThat(service.recordSafely(draft)).isTrue();

        ArgumentCaptor<IntentDecisionTrace> traceCaptor =
                ArgumentCaptor.forClass(IntentDecisionTrace.class);
        verify(writer).write(traceCaptor.capture());
        IntentDecisionTrace stored = traceCaptor.getValue();
        assertThat(stored.getRawInputEncrypted()).isNull();
        assertThat(stored.getRawOutputEncrypted()).isNull();
        assertThat(stored.getRawCipherKeyId()).isNull();
        assertThat(stored.getRawExpiresAt()).isNull();
        assertThat(stored.getRedactedSummary()).isEqualTo("safe summary");
    }

    @Test
    void writerFailureNeverEscapesToTheBusinessOperation() {
        IntentDecisionTraceWriter writer = mock(IntentDecisionTraceWriter.class);
        doThrow(new IllegalStateException("database unavailable")).when(writer).write(any());
        IntentDecisionTraceService service = new IntentDecisionTraceService(
                writer, new NoRawSecretTextCipher(), PROPERTIES, CLOCK);

        boolean recorded = service.recordSafely(
                IntentDecisionTraceDraft.builder(UUID.randomUUID(), "REST").build());

        assertThat(recorded).isFalse();
    }

    @Test
    void nullDraftIsContained() {
        IntentDecisionTraceService service = new IntentDecisionTraceService(
                mock(IntentDecisionTraceWriter.class), new NoRawSecretTextCipher(),
                PROPERTIES, CLOCK);

        assertThat(service.recordSafely(null)).isFalse();
    }
}

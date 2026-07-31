package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.scheduling.enabled=false",
        "app.intent.enabled=true"
})
@ActiveProfiles("local")
@Import(AnthropicExactRouteProbeSupport.ProbeConfiguration.class)
@EnabledIfSystemProperty(named = "liveIntentExactRouteProbe", matches = "true")
class AnthropicExactRouteLatencyProbeTest {

    private static final String SCENARIO_ID = "calendar-analysis-107";
    private static final String MESSAGE =
            "告訴我下週所有行程裡哪一天最忙、最長的一筆是哪一筆，並列出那筆行程前後還各剩多少空檔";
    private static final Instant NOW = Instant.parse("2026-07-17T05:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Taipei"));
    private static final int MEASURED_SAMPLES = 10;
    private static final long TERMINAL_P95_LIMIT_MILLIS = 4_000L;
    private static final Path REPORT = Path.of("target", "anthropic-exact-route-probe.md");
    private static final Set<IntentCommand.Type> REQUIRED_TYPES = EnumSet.of(
            IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
            IntentCommand.Type.ASK_LONGEST_SCHEDULE,
            IntentCommand.Type.UNKNOWN);

    @Autowired
    private AnthropicIntentInterpreter interpreter;

    @Test
    void measuresCacheRetryAndTransportOnTheExactSynchronousRoute() throws IOException {
        evaluate("warmup", 0);

        List<Sample> samples = new ArrayList<>();
        for (int index = 1; index <= MEASURED_SAMPLES; index++) {
            Sample sample = evaluate("measured", index);
            samples.add(sample);
            print(sample);
        }

        long terminalP95Millis = percentile(
                samples.stream().map(Sample::totalLatencyMillis).toList(), 0.95);
        long correct = samples.stream().filter(sample -> "PASS".equals(sample.correctness())).count();
        System.out.printf(Locale.ROOT,
                "ANTHROPIC_EXACT_ROUTE_SUMMARY scenario=%s samples=%d correct=%d terminalP95Ms=%d limitMs=%d%n",
                SCENARIO_ID, samples.size(), correct, terminalP95Millis,
                TERMINAL_P95_LIMIT_MILLIS);
        Files.createDirectories(REPORT.getParent());
        String rows = samples.stream().map(sample -> "- sample=%d correctness=%s attempts=%d "
                        .formatted(sample.sampleNumber(), sample.correctness(), sample.attemptCount())
                        + "inputTok=" + sample.inputTokens()
                        + " outputTok=" + sample.outputTokens()
                        + " cacheCreateTok=" + sample.cacheCreationInputTokens()
                        + " cacheReadTok=" + sample.cacheReadInputTokens()
                        + " headersMs=" + sample.responseHeadersMillis()
                        + " firstByteMs=" + sample.responseFirstByteMillis()
                        + " bodyMs=" + sample.responseBodyMillis()
                        + " modelMs=" + sample.modelLatencyMillis()
                        + " totalMs=" + sample.totalLatencyMillis())
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(REPORT, """
                # Anthropic exact-route metadata probe

                - Samples: %d measured after 1 warm-up
                - Correct: %d/%d
                - Total P95: %d ms
                - Limit: %d ms

                %s
                """.formatted(samples.size(), correct, samples.size(), terminalP95Millis,
                        TERMINAL_P95_LIMIT_MILLIS, rows), StandardCharsets.UTF_8);
        if (correct != samples.size()) {
            fail("exact-route-correctness-failed");
        }
        if (terminalP95Millis > TERMINAL_P95_LIMIT_MILLIS) {
            fail("exact-route-terminal-p95-exceeded");
        }
    }

    private Sample evaluate(String phase, int sampleNumber) {
        long startedNanos = System.nanoTime();
        try (AnthropicExactRouteProbeSupport.Scope probe =
                     AnthropicExactRouteProbeSupport.open(SCENARIO_ID);
             IntentInterpreterTelemetryContext.Scope telemetry =
                     IntentInterpreterTelemetryContext.open()) {
            IntentScript interpreted;
            try {
                interpreted = interpreter.interpret(
                        MESSAGE, NOW, ConversationSnapshot.empty());
            } catch (RuntimeException exception) {
                throw new AssertionError("exact-route-provider-call-failed");
            }
            IntentScript safe = IntentScriptCompletenessPolicy.apply(
                    MESSAGE,
                    IntentScriptSafetyPolicy.applyStrict(MESSAGE, interpreted, CLOCK));
            String correctness = assessRequiredFacets(safe);

            AnthropicExactRouteProbeSupport.Snapshot probeSnapshot = probe.snapshot();
            IntentInterpreterTelemetryContext.Telemetry modelTelemetry = telemetry.snapshot();
            assertProbeComplete(probeSnapshot, modelTelemetry);
            return sample(
                    phase,
                    sampleNumber,
                    probeSnapshot,
                    modelTelemetry,
                    elapsedMillis(startedNanos),
                    correctness);
        }
    }

    private static String assessRequiredFacets(IntentScript script) {
        if (script == null || script.commands() == null || script.commands().size() != 3) {
            return "COMMAND_COUNT";
        }
        Set<IntentCommand.Type> actualTypes = EnumSet.noneOf(IntentCommand.Type.class);
        for (IntentCommand command : script.commands()) {
            if (command == null || command.type() == null
                    || !REQUIRED_TYPES.contains(command.type())) {
                return "TYPE_MISMATCH";
            }
            actualTypes.add(command.type());
            if (command.type() == IntentCommand.Type.UNKNOWN
                    && (command.reason() == null || command.reason().isBlank())) {
                return "CLARIFICATION_MISSING";
            }
        }
        if (!actualTypes.equals(REQUIRED_TYPES)) {
            return "FACETS_MISSING";
        }
        return "PASS";
    }

    private static void assertProbeComplete(
            AnthropicExactRouteProbeSupport.Snapshot probe,
            IntentInterpreterTelemetryContext.Telemetry telemetry) {
        if (probe.attemptCount() < 1) {
            fail("exact-route-http-attempt-missing");
        }
        if (probe.inputTokens() == null || probe.outputTokens() == null
                || probe.cacheCreationInputTokens() == null
                || probe.cacheReadInputTokens() == null) {
            fail("exact-route-native-usage-missing");
        }
        AnthropicExactRouteProbeSupport.AttemptSnapshot attempt = probe.finalAttempt();
        if (attempt.responseHeadersMillis() == null
                || attempt.responseFirstByteMillis() == null
                || attempt.responseBodyMillis() == null) {
            fail("exact-route-transport-timing-missing");
        }
        if (telemetry == null || telemetry.modelLatencyMs() == null) {
            fail("exact-route-model-timing-missing");
        }
    }

    private static Sample sample(
            String phase,
            int sampleNumber,
            AnthropicExactRouteProbeSupport.Snapshot probe,
            IntentInterpreterTelemetryContext.Telemetry telemetry,
            long totalLatencyMillis,
            String correctness) {
        AnthropicExactRouteProbeSupport.AttemptSnapshot attempt = probe.finalAttempt();
        return new Sample(
                phase,
                sampleNumber,
                probe.attemptCount(),
                probe.retryGapMillis(),
                probe.inputTokens(),
                probe.outputTokens(),
                probe.cacheCreationInputTokens(),
                probe.cacheReadInputTokens(),
                attempt.responseHeadersMillis(),
                attempt.responseFirstByteMillis(),
                attempt.responseBodyMillis(),
                telemetry.modelLatencyMs(),
                totalLatencyMillis,
                correctness);
    }

    private static void print(Sample sample) {
        System.out.printf(Locale.ROOT,
                "ANTHROPIC_EXACT_ROUTE scenario=%s phase=%s sample=%d attempts=%d "
                        + "retryGapMs=%d inputTok=%d outputTok=%d cacheCreateTok=%d "
                        + "cacheReadTok=%d responseHeadersMs=%d responseFirstByteMs=%d "
                        + "responseBodyMs=%d modelMs=%d totalMs=%d correctness=%s%n",
                SCENARIO_ID,
                sample.phase(),
                sample.sampleNumber(),
                sample.attemptCount(),
                sample.retryGapMillis(),
                sample.inputTokens(),
                sample.outputTokens(),
                sample.cacheCreationInputTokens(),
                sample.cacheReadInputTokens(),
                sample.responseHeadersMillis(),
                sample.responseFirstByteMillis(),
                sample.responseBodyMillis(),
                sample.modelLatencyMillis(),
                sample.totalLatencyMillis(),
                sample.correctness());
    }

    private static long percentile(List<Long> values, double quantile) {
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private record Sample(
            String phase,
            int sampleNumber,
            int attemptCount,
            Long retryGapMillis,
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheCreationInputTokens,
            Integer cacheReadInputTokens,
            Long responseHeadersMillis,
            Long responseFirstByteMillis,
            Long responseBodyMillis,
            Long modelLatencyMillis,
            long totalLatencyMillis,
            String correctness) {
    }
}

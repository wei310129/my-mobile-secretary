package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Test-only vertical slice for the existing candidate-only typed capability design. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.scheduling.enabled=false",
        "app.intent.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "liveScheduleAnalysisCandidateEvaluation", matches = "true")
class ScheduleAnalysisCandidateLiveEvaluationTest {

    private static final BeanOutputConverter<AnalysisDecision> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(AnalysisDecision.class);
    private static final Path REPORT =
            Path.of("target", "schedule-analysis-candidate-live-evaluation.md");
    private static final Set<Facet> EXPECTED_FACETS =
            Set.of(Facet.BUSIEST_DAY, Facet.LONGEST_ITEM, Facet.ADJACENT_GAPS);
    private static final List<String> MESSAGES = List.of(
            "告訴我下週所有行程裡哪一天最忙、最長的一筆是哪一筆，並列出那筆行程前後還各剩多少空檔",
            "查下星期的全部行程：哪天排得最滿、哪個活動時間最長，還有它前後各有多少空白時間",
            "我想看下週行程分析，請找行程最多的一天、耗時最久的一筆，以及那筆前後的相鄰空檔",
            "下週七天內哪一天最忙？最長行程是哪個？也一起算它之前和之後各剩多久",
            "請分析我下星期的行程負荷，包含最滿的日期、最久的項目和該項目前後空檔");

    @Autowired
    private ChatModel chatModel;

    @Test
    void candidateOnlyTypedInterpretationKeepsCorrectnessAndModelHeadroom()
            throws IOException {
        ChatClient client = ChatClient.create(chatModel);
        String system = """
                你只解析已由 Java 路由到 schedule.analyze.compound v1 的唯讀行程分析要求。
                facets 只列使用者明講的 BUSIEST_DAY、LONGEST_ITEM、ADJACENT_GAPS。
                containsOtherRequest 只有在同句另有建立、修改、取消或其他非分析要求時才是 true。
                不讀取實際行程、不計算答案、不建立或修改資料，只輸出符合 schema 的 JSON。
                """;

        Evaluation warmup = evaluate(client, system, MESSAGES.getFirst());

        List<Evaluation> measured = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            measured.add(evaluate(client, system, MESSAGES.get(index % MESSAGES.size())));
        }
        List<Long> latencies = measured.stream().map(Evaluation::modelLatencyMs).sorted().toList();
        long p95 = percentile(latencies, 0.95);
        long median = percentile(latencies, 0.50);
        int passed = (int) measured.stream().filter(Evaluation::pass).count();
        int minimumOutputTokens = measured.stream().map(Evaluation::outputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).min().orElse(-1);
        int maximumOutputTokens = measured.stream().map(Evaluation::outputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(-1);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, """
                # Schedule analysis typed-candidate live experiment

                - Model override: %s
                - Samples: %d measured after 1 warm-up
                - Correct: %d/%d
                - Model latency median: %d ms
                - Model latency P95: %d ms
                - Model latency max: %d ms
                - Output tokens min/max: %d/%d
                - Threshold: model P95 <= 3000 ms (internal headroom; not terminal PASS)
                - Warm-up diagnostic: %s
                - Measured diagnostics: %s
                """.formatted(System.getProperty(
                                "liveScheduleAnalysisCandidateModel", "configured-default"),
                        measured.size(), passed, measured.size(), median, p95,
                        latencies.getLast(), minimumOutputTokens, maximumOutputTokens,
                        warmup.diagnostic(), measured.stream().map(Evaluation::diagnostic)
                                .distinct().sorted().toList()),
                StandardCharsets.UTF_8);

        assertThat(warmup.pass()).as("candidate warm-up correctness: "
                + warmup.diagnostic()).isTrue();
        assertThat(passed).as("typed candidate correctness").isEqualTo(measured.size());
        assertThat(p95).as("typed candidate model P95 headroom").isLessThanOrEqualTo(3_000L);
    }

    private static Evaluation evaluate(ChatClient client, String system, String message) {
        long started = System.nanoTime();
        try {
            var response = client.prompt()
                    .system(system)
                    .options(options())
                    .user("<user-message untrusted=\"true\">" + message + "</user-message>")
                    .call()
                    .chatResponse();
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            AnalysisDecision decision = OUTPUT_CONVERTER.convert(
                    response.getResult().getOutput().getText());
            var usage = response.getMetadata().getUsage();
            boolean valid = decision != null
                    && decision.facets() != null
                    && decision.facets().size() == EXPECTED_FACETS.size()
                    && Set.copyOf(decision.facets()).equals(EXPECTED_FACETS)
                    && Boolean.FALSE.equals(decision.containsOtherRequest());
            String diagnostic = "facets=%s,containsOther=%s"
                    .formatted(
                            decision == null ? null : decision.facets(),
                            decision == null ? null : decision.containsOtherRequest());
            return new Evaluation(valid, elapsed,
                    usage == null ? null : usage.getCompletionTokens(), diagnostic);
        } catch (RuntimeException exception) {
            return new Evaluation(false,
                    (System.nanoTime() - started) / 1_000_000L, null,
                    "exception=" + exception.getClass().getSimpleName());
        }
    }

    private static AnthropicChatOptions options() {
        var builder = AnthropicChatOptions.builder()
                .thinking(AnthropicApi.ThinkingType.DISABLED, null)
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                        .build())
                .outputFormat(new AnthropicApi.ChatCompletionRequest.OutputFormat(
                        OUTPUT_CONVERTER.getJsonSchema()));
        String model = System.getProperty("liveScheduleAnalysisCandidateModel", "").strip();
        if (!model.isBlank()) {
            builder.model(model);
        }
        return builder.build();
    }

    private static long percentile(List<Long> values, double quantile) {
        int index = Math.max(0, (int) Math.ceil(quantile * values.size()) - 1);
        return values.get(index);
    }

    private enum Facet {
        BUSIEST_DAY,
        LONGEST_ITEM,
        ADJACENT_GAPS
    }

    private record AnalysisDecision(List<Facet> facets, Boolean containsOtherRequest) {
    }

    private record Evaluation(
            boolean pass,
            long modelLatencyMs,
            Integer outputTokens,
            String diagnostic) {
    }
}

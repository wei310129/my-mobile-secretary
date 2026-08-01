package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiScheduleAnalysisUnderstandingClientTest {

    private static final Path LIVE_REPORT =
            Path.of("target", "openai-schedule-analysis-production-route-live.md");

    @Test
    void sendsOneBoundedTwoRoleStructuredRequestAndConvertsTypedDecision() {
        String text = "下週哪一天最忙、哪筆最久、前後空多久？";
        OpenAiScheduleAnalysisProperties properties = properties("test-key");
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(ExpectedCount.once(), requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gpt-5.6-luna"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content")
                        .value(OpenAiScheduleAnalysisUnderstandingClient.systemPrompt()))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(text))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.max_completion_tokens").value(256))
                .andExpect(jsonPath("$.n").value(1))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "chatcmpl-prod-contract",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "gpt-5.6-luna-2026-07-17",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "{\\\"facets\\\":[\\\"BUSIEST_DAY\\\",\\\"LONGEST_ITEM\\\",\\\"ADJACENT_GAPS\\\"],\\\"containsOtherRequest\\\":false}"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 100,
                            "completion_tokens": 20,
                            "total_tokens": 120
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));
        OpenAiScheduleAnalysisUnderstandingClient client =
                new OpenAiScheduleAnalysisUnderstandingClient(
                        properties, restClientBuilder, WebClient.builder());

        ScheduleAnalysisUnderstandingClient.Decision decision = client.understand(text);

        assertThat(decision.facets()).containsExactly(
                ScheduleAnalysisUnderstandingClient.Facet.BUSIEST_DAY,
                ScheduleAnalysisUnderstandingClient.Facet.LONGEST_ITEM,
                ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS);
        assertThat(decision.containsOtherRequest()).isFalse();
        server.verify();
    }

    @Test
    void blankCredentialMakesAdapterUnavailableWithoutConstructingARequest() {
        OpenAiScheduleAnalysisUnderstandingClient client =
                new OpenAiScheduleAnalysisUnderstandingClient(properties(""));

        assertThat(client.available()).isFalse();
    }

    @Test
    void routeGrammarCoversSemanticMatrixButRejectsNeighborRequests() {
        ScheduleAnalysisRoutePolicy policy = new ScheduleAnalysisRoutePolicy();

        assertThat(List.of(
                "告訴我下週哪一天最忙、最長的一筆是哪筆，並列出那筆前後各剩多少空檔",
                "查下星期行程：哪天排最滿、哪個活動最久，還有它前後的空白時間",
                "下週七天裡行程最多的是哪天？耗時最長的是哪個？前後還剩多久？",
                "分析下禮拜的行程負荷，包含最忙日期、最長項目和相鄰空檔",
                "我想知道下週最擠的一天、佔時間最久的行程，以及那筆前後的空檔",
                "下星期哪天安排最多，最久的活動是什麼，它前一段跟後一段各空多久",
                "幫我整理下週：最滿日、最長行程、最長那筆前後可用的時間",
                "下週哪一天最忙碌？哪筆行程時間最長？它的前後空檔也請算出來",
                "下禮拜行程數最多哪天？最久那ㄧ筆咧，前後空多久",
                "找下週最長行程，然後新增週五兩點開會"))
                .allMatch(policy::matches);
        assertThat(List.of(
                "下週有什麼行程？",
                "你剛才把我的行程分析錯了",
                "下週三點到四點有一個很長的會議",
                "哪一天待辦最多？"))
                .noneMatch(policy::matches);
    }

    @Test
    @EnabledIfSystemProperty(
            named = "liveOpenAiScheduleAnalysisProductionRoute",
            matches = "true")
    void productionRouteMeetsTypedCorrectnessAndLatencyHeadroom() throws IOException {
        String apiKey = firstNonBlank(
                System.getenv("SPRING_AI_OPENAI_API_KEY"),
                System.getenv("OPENAI_API_KEY"));
        assertThat(apiKey).as("OpenAI credential must be configured outside version control")
                .isNotBlank();
        OpenAiScheduleAnalysisProperties properties = properties(apiKey);
        OpenAiScheduleAnalysisUnderstandingClient client =
                new OpenAiScheduleAnalysisUnderstandingClient(properties);
        AnthropicIntentInterpreter legacy = org.mockito.Mockito.mock(
                AnthropicIntentInterpreter.class);
        ProviderNeutralIntentInterpreter interpreter = new ProviderNeutralIntentInterpreter(
                legacy, client, new ScheduleAnalysisRoutePolicy());
        List<LiveScenario> scenarios = List.of(
                new LiveScenario(
                        "告訴我下週哪一天最忙、最長的一筆是哪筆，並列出那筆前後各剩多少空檔",
                        List.of(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                                IntentCommand.Type.UNKNOWN)),
                new LiveScenario(
                        "下星期哪天安排最多，最久的活動是什麼，它前一段跟後一段各空多久",
                        List.of(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                                IntentCommand.Type.UNKNOWN)),
                new LiveScenario(
                        "分析下週最忙的一天，另外取消星期三牙醫",
                        List.of(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                                IntentCommand.Type.UNKNOWN)),
                new LiveScenario(
                        "下星期最久的那筆活動左右還有多少空白時間",
                        List.of(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                                IntentCommand.Type.UNKNOWN)));

        interpreter.interpret(scenarios.getFirst().text(), Instant.now());
        List<Long> elapsed = new ArrayList<>();
        for (LiveScenario scenario : scenarios) {
            long started = System.nanoTime();
            IntentScript script = interpreter.interpret(scenario.text(), Instant.now());
            elapsed.add(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            assertThat(script.commands()).extracting(IntentCommand::type)
                    .containsExactlyElementsOf(scenario.expectedTypes());
            assertThat(script.commands()).noneMatch(command ->
                    IntentService.isPotentiallyMutating(command.type()));
        }
        List<Long> sorted = elapsed.stream().sorted().toList();
        long p95 = sorted.getLast();
        Files.createDirectories(LIVE_REPORT.getParent());
        Files.writeString(LIVE_REPORT, """
                # OpenAI production schedule-analysis route live gate

                - Model: gpt-5.6-luna
                - Warm-up: 1
                - Measured synthetic scenarios: %d
                - Correct typed scripts: %d/%d
                - Terminal route latency median: %d ms
                - Terminal route latency P95/max: %d ms
                - Attempts per scenario: 1
                - Business mutations: 0
                """.formatted(
                        scenarios.size(), scenarios.size(), scenarios.size(),
                        sorted.get((sorted.size() - 1) / 2), p95),
                StandardCharsets.UTF_8);

        assertThat(p95).as("production typed-route P95 headroom")
                .isLessThanOrEqualTo(3_000L);
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    private static OpenAiScheduleAnalysisProperties properties(String apiKey) {
        OpenAiScheduleAnalysisProperties properties = new OpenAiScheduleAnalysisProperties();
        properties.setApiKey(apiKey);
        return properties;
    }

    private static String firstNonBlank(String primary, String secondary) {
        return primary != null && !primary.isBlank() ? primary : secondary;
    }

    private record LiveScenario(String text, List<IntentCommand.Type> expectedTypes) {
    }
}

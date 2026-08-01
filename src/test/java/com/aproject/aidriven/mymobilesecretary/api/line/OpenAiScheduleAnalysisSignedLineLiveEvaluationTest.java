package com.aproject.aidriven.mymobilesecretary.api.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogRepository;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.ProviderNeutralIntentInterpreter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Opt-in live-model gate through the signed LINE entry with outbound messaging disabled. */
@SpringBootTest(properties = "app.intent.enabled=true")
@AutoConfigureMockMvc
@Import(OpenAiScheduleAnalysisSignedLineLiveEvaluationTest.InfrastructureOnly.class)
@ActiveProfiles("test")
@Sql(scripts = "/reset-integration-test-data.sql",
        config = @SqlConfig(separator = "^^^"),
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@EnabledIfSystemProperty(
        named = "liveOpenAiScheduleAnalysisSignedLineEvaluation",
        matches = "true")
class OpenAiScheduleAnalysisSignedLineLiveEvaluationTest {

    private static final String TEST_SECRET = "test-channel-secret";
    private static final String OWNER_USER_ID = "test-owner-user";
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Path REPORT =
            Path.of("target", "openai-schedule-analysis-signed-line-live.md");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LineMessageLogRepository messages;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IntentInterpreter interpreter;
    @MockitoBean
    private LineMessagingClient messagingClient;

    @Test
    void signedLineColdAndWarmRequestsStayReadOnlyCorrectAndWithinBudget()
            throws Exception {
        assertThat(firstNonBlank(
                System.getenv("SPRING_AI_OPENAI_API_KEY"),
                System.getenv("OPENAI_API_KEY")))
                .as("OpenAI credential must be configured outside version control")
                .isNotBlank();
        assertThat(interpreter).isInstanceOf(ProviderNeutralIntentInterpreter.class);
        long legacyBefore = count("schedule_item");
        long calendarBefore = count("calendar_plan");
        List<String> turns = List.of(
                "告訴我下週哪一天最忙、最長的一筆是哪筆，並列出那筆前後各剩多少空檔",
                "查下星期行程：哪天排最滿、哪個活動最久，還有它前後的空白時間",
                "下週七天裡行程最多的是哪天？耗時最長的是哪個？前後還剩多久？",
                "下星期哪天安排最多，最久的活動是什麼，它前一段跟後一段各空多久",
                "下禮拜行程數最多哪天？最久那ㄧ筆咧，前後空多久");
        List<Long> elapsed = new ArrayList<>();

        for (String turn : turns) {
            long started = System.nanoTime();
            send(turn);
            elapsed.add(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            assertThat(latestReply())
                    .contains("指定範圍內沒有已確認行程", "相鄰空檔", "不會建立或修改資料")
                    .doesNotContain("OpenAI", "Luna", "ASK_", "Intent", "schema",
                            ACTOR_ID.toString(), WORKSPACE_ID.toString());
        }

        assertThat(count("schedule_item")).isEqualTo(legacyBefore);
        assertThat(count("calendar_plan")).isEqualTo(calendarBefore);
        org.mockito.Mockito.verify(messagingClient,
                org.mockito.Mockito.times(turns.size()))
                .reply(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        List<Long> warm = elapsed.subList(1, elapsed.size()).stream().sorted().toList();
        long cold = elapsed.getFirst();
        long warmP95 = warm.getLast();
        writeReport(cold, warm, warmP95, turns.size());

        assertThat(cold).as("signed LINE cold terminal milliseconds")
                .isLessThanOrEqualTo(4_000L);
        assertThat(warmP95).as("signed LINE warm P95 terminal milliseconds")
                .isLessThanOrEqualTo(4_000L);
    }

    private void send(String text) throws Exception {
        String eventId = "openai-live-" + UUID.randomUUID();
        byte[] body = textMessageEvent(text, eventId);
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String latestReply() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            return messages
                    .findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                            WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 10))
                    .stream()
                    .filter(message -> message.getDirection() == LineMessageLog.Direction.OUT)
                    .findFirst()
                    .orElseThrow()
                    .getContent();
        }
    }

    private long count(String table) {
        if (!List.of("schedule_item", "calendar_plan").contains(table)) {
            throw new IllegalArgumentException("unsupported table");
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static byte[] textMessageEvent(String text, String eventId) {
        return """
                {"events":[{"type":"message","replyToken":"rt-%s","webhookEventId":"%s",\
                "source":{"userId":"%s"},\
                "message":{"id":"message-%s","type":"text","text":"%s"}}]}
                """.formatted(eventId, eventId, OWNER_USER_ID, eventId, text)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private static WorkspaceContext lineContext() {
        return new WorkspaceContext(ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.LINE,
                "line", "user:" + OWNER_USER_ID);
    }

    private static String firstNonBlank(String primary, String secondary) {
        return primary != null && !primary.isBlank() ? primary : secondary;
    }

    private static void writeReport(
            long cold, List<Long> warm, long warmP95, int turns) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, """
                # OpenAI schedule-analysis signed LINE live gate

                - Model: gpt-5.6-luna
                - Signed synthetic turns: %d (do not count toward personal monitoring)
                - Cold terminal latency: %d ms
                - Warm terminal median: %d ms
                - Warm terminal P95/max: %d ms
                - Provider attempts per turn: 1
                - LINE outbound provider calls: 0 (mocked at the outer adapter)
                - Legacy schedule delta: 0
                - Calendar V2 plan delta: 0
                """.formatted(turns, cold, warm.get((warm.size() - 1) / 2), warmP95),
                StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InfrastructureOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:16-3.4")
                            .asCompatibleSubstituteFor("postgres"));
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
        }
    }
}

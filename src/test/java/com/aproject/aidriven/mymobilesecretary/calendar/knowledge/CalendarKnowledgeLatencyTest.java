package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@Tag("calendar-knowledge-latency")
@EnabledIfSystemProperty(
        named = "calendar.knowledge.latency.enabled",
        matches = "true")
@TestMethodOrder(OrderAnnotation.class)
class CalendarKnowledgeLatencyTest extends IntegrationTestBase {

    private static final int WARM_UP = 5;
    private static final int SAMPLES = 30;
    private static final long LOW_P95_MILLIS = 1_500;
    private static final long MEDIUM_P95_MILLIS = 4_000;
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final List<ColdSample> COLD_SAMPLES =
            Collections.synchronizedList(new ArrayList<>());

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarKnowledgeConversationService conversations;
    @Autowired private UserKnowledgeService knowledge;
    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void clearColdSamples() {
        COLD_SAMPLES.clear();
    }

    @Test
    @Order(1)
    void warmDirectAndActualApiEntryStayWithinApprovedP95() throws Exception {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID,
                WORKSPACE_ID,
                WorkspaceChannel.TEST,
                "latency",
                "calendar-knowledge");
        IntentCommand ask;
        try (var ignored = WorkspaceContextHolder.open(context)) {
            UserKnowledgeFact fact = knowledge.remember(
                    UserKnowledgeFact.Category.PLACE_GUIDANCE,
                    "登船證件",
                    "出發前確認護照效期");
            var plan = calendars.createPlanWithIdentity(new CreateCalendarPlanCommand(
                    "latency-plan-" + UUID.randomUUID(),
                    "延遲測試旅行",
                    CalendarPlacement.point(
                            Instant.parse("2027-08-01T00:00:00Z"),
                            ZoneId.of("Asia/Taipei")),
                    "TRAVEL",
                    null,
                    null,
                    List.of(),
                    List.of()));
            bindings.bindFact(
                    "latency-bind-" + UUID.randomUUID(),
                    fact.getId(),
                    CalendarKnowledgeTarget.plan(plan.planId()));
            ask = askCommand();
        }

        List<Long> direct = new ArrayList<>();
        List<Long> api = new ArrayList<>();
        for (int index = 0; index < WARM_UP + SAMPLES; index++) {
            try (var ignored = WorkspaceContextHolder.open(context)) {
                long directStarted = System.nanoTime();
                IntentResult directResult = conversations.ask(ask);
                long directElapsed = System.nanoTime() - directStarted;
                assertThat(directResult.action())
                        .isEqualTo(IntentResult.Action.CALENDAR_KNOWLEDGE_LISTED);
                if (index >= WARM_UP) {
                    direct.add(directElapsed);
                }
            }

            interpreter.nextCommand(ask);
            long apiStarted = System.nanoTime();
            String response = mockMvc.perform(post("/api/intent")
                            .header("X-Request-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"text":"旅行的登船證件寫了什麼"}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            long apiElapsed = System.nanoTime() - apiStarted;
            assertThat(response).contains("CALENDAR_KNOWLEDGE_LISTED");
            if (index >= WARM_UP) {
                api.add(apiElapsed);
            }
        }

        assertThat(direct).hasSize(SAMPLES);
        assertThat(api).hasSize(SAMPLES);
        Latency directLatency = latency(direct);
        Latency apiLatency = latency(api);
        assertThat(directLatency.p95Millis())
                .as("direct deterministic Calendar knowledge P95")
                .isLessThanOrEqualTo(LOW_P95_MILLIS);
        assertThat(apiLatency.p95Millis())
                .as("actual /api/intent Calendar knowledge P95")
                .isLessThanOrEqualTo(MEDIUM_P95_MILLIS);
        System.out.printf(
                "CALENDAR_KNOWLEDGE_LATENCY environment=testcontainers "
                        + "providerMode=controlled-stub channel=API entry=/api/intent "
                        + "warm=true firstMeaningful=terminal samples=%d "
                        + "directMedianMs=%d directP95Ms=%d directMaxMs=%d "
                        + "apiMedianMs=%d apiP95Ms=%d apiMaxMs=%d%n",
                SAMPLES,
                directLatency.medianMillis(),
                directLatency.p95Millis(),
                directLatency.maximumMillis(),
                apiLatency.medianMillis(),
                apiLatency.p95Millis(),
                apiLatency.maximumMillis());
    }

    @Test
    @Order(10)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldApiInboundOneUsesFreshContext() throws Exception {
        measureColdApiInbound(1, false);
    }

    @Test
    @Order(20)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldApiInboundTwoUsesFreshContext() throws Exception {
        measureColdApiInbound(2, false);
    }

    @Test
    @Order(30)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldApiInboundThreeUsesFreshContext() throws Exception {
        measureColdApiInbound(3, false);
    }

    @Test
    @Order(40)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldApiInboundFourUsesFreshContext() throws Exception {
        measureColdApiInbound(4, false);
    }

    @Test
    @Order(50)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldApiInboundFiveAggregatesIndependentContexts() throws Exception {
        measureColdApiInbound(5, true);
    }

    private void measureColdApiInbound(int sample, boolean assertAggregate)
            throws Exception {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_knowledge_fact_binding",
                        Long.class))
                .isZero();
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID,
                WORKSPACE_ID,
                WorkspaceChannel.TEST,
                "latency",
                "calendar-knowledge-cold-" + sample);
        IntentCommand ask;
        try (var ignored = WorkspaceContextHolder.open(context)) {
            UserKnowledgeFact fact = knowledge.remember(
                    UserKnowledgeFact.Category.PLACE_GUIDANCE,
                    "冷啟動登船證件 " + sample,
                    "出發前確認護照效期");
            var plan = calendars.createPlanWithIdentity(
                    new CreateCalendarPlanCommand(
                            "latency-cold-plan-" + sample,
                            "冷啟動旅行 " + sample,
                            CalendarPlacement.point(
                                    Instant.parse("2027-08-01T00:00:00Z")
                                            .plusSeconds(sample),
                                    ZoneId.of("Asia/Taipei")),
                            "TRAVEL",
                            null,
                            null,
                            List.of(),
                            List.of()));
            bindings.bindFact(
                    "latency-cold-bind-" + sample,
                    fact.getId(),
                    CalendarKnowledgeTarget.plan(plan.planId()));
            ask = askCommand(
                    "冷啟動登船證件 " + sample,
                    "冷啟動旅行 " + sample);
        }
        interpreter.nextCommand(ask);

        long started = System.nanoTime();
        String response = mockMvc.perform(post("/api/intent")
                        .header("X-Request-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"冷啟動旅行的登船證件寫了什麼"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long elapsed = System.nanoTime() - started;
        assertThat(response).contains("CALENDAR_KNOWLEDGE_LISTED");
        COLD_SAMPLES.add(new ColdSample(
                System.identityHashCode(applicationContext), elapsed));

        if (assertAggregate) {
            assertThat(COLD_SAMPLES).hasSize(5);
            assertThat(COLD_SAMPLES.stream()
                            .map(ColdSample::contextIdentity)
                            .distinct())
                    .hasSize(5);
            Latency cold = latency(COLD_SAMPLES.stream()
                    .map(ColdSample::elapsedNanos)
                    .toList());
            assertThat(cold.p95Millis())
                    .as("five application-cold actual /api/intent P95")
                    .isLessThanOrEqualTo(MEDIUM_P95_MILLIS);
            System.out.printf(
                    "CALENDAR_KNOWLEDGE_LATENCY environment=testcontainers "
                            + "providerMode=controlled-stub channel=API "
                            + "entry=/api/intent warm=false "
                            + "coldDefinition=fresh-application-context "
                            + "firstMeaningful=terminal samples=5 "
                            + "medianMs=%d p95Ms=%d maxMs=%d%n",
                    cold.medianMillis(),
                    cold.p95Millis(),
                    cold.maximumMillis());
        }
    }

    private static IntentCommand askCommand() {
        return askCommand("登船證件", "延遲測試旅行");
    }

    private static IntentCommand askCommand(String title, String planTitle) {
        return new IntentCommand(
                IntentCommand.Type.ASK_CALENDAR_KNOWLEDGE,
                title,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new IntentOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "PLAN",
                        null,
                        null,
                        planTitle,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private static Latency latency(List<Long> samples) {
        List<Long> ordered =
                samples.stream().sorted(Comparator.naturalOrder()).toList();
        return new Latency(
                millis(ordered.get((ordered.size() - 1) / 2)),
                millis(ordered.get((int) Math.ceil(ordered.size() * 0.95) - 1)),
                millis(ordered.getLast()));
    }

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private record Latency(
            long medianMillis, long p95Millis, long maximumMillis) {}

    private record ColdSample(int contextIdentity, long elapsedNanos) {}
}

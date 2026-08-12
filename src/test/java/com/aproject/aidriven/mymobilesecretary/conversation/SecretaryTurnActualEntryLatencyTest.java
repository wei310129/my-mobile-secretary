package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SecretaryTurnActualEntryLatencyTest extends IntegrationTestBase {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final int SAMPLE_COUNT = 60;
    private static final Duration P95_LIMIT = Duration.ofMillis(1_500);

    @Autowired private IntentService intentService;
    @Autowired private IntentDecisionTraceRepository traceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void genericTodayQuestionUsesReadOnlySecretaryRouteWithinLatencyGate() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "secretary-latency");
        Map<String, Long> mutationsBefore = mutationCounts();
        List<Long> elapsedMillis = new ArrayList<>();
        List<UUID> measuredRequestIds = new ArrayList<>();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            handle(UUID.randomUUID());
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                UUID requestId = UUID.randomUUID();
                long startedNanos = System.nanoTime();

                IntentResult result = handle(requestId);

                elapsedMillis.add(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                measuredRequestIds.add(requestId);
                assertThat(result.action()).isEqualTo(IntentResult.Action.AGENDA_LISTED);
                assertThat(result.message()).contains("現在", "接下來");
            }
        }

        long p95Millis = percentile95(elapsedMillis);
        System.out.printf("SECRETARY_ACTUAL_ENTRY samples_ms=%s%n", elapsedMillis);
        assertThat(mutationCounts()).isEqualTo(mutationsBefore);
        assertThat(p95Millis).isLessThanOrEqualTo(P95_LIMIT.toMillis());
        System.out.printf(
                "SECRETARY_ACTUAL_ENTRY samples=%d p95_ms=%d max_ms=%d business_mutations=0%n",
                SAMPLE_COUNT, p95Millis, elapsedMillis.stream().mapToLong(Long::longValue).max().orElse(0));
        for (UUID requestId : measuredRequestIds) {
            IntentDecisionTrace trace = traceRepository.findByRequestId(requestId).orElseThrow();
            assertThat(trace.getSelectedCapability()).isEqualTo("LIST_AGENDA");
            assertThat(trace.getModel()).isNull();
            assertThat(trace.getInputTokens()).isNull();
            assertThat(trace.getOutputTokens()).isNull();
            assertThat(trace.getStageLatenciesMs()).doesNotContainKey("model");
        }
    }

    @Test
    void systemPlaceCategoryUsesDeterministicRouteWithinLatencyGate() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "place-latency");
        Map<String, Long> mutationsBefore = mutationCounts();
        List<Long> elapsedMillis = new ArrayList<>();
        List<UUID> measuredRequestIds = new ArrayList<>();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            handle(UUID.randomUUID(), "捷運站");
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                UUID requestId = UUID.randomUUID();
                long startedNanos = System.nanoTime();

                IntentResult result = handle(requestId, "捷運站");

                elapsedMillis.add(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                measuredRequestIds.add(requestId);
                assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
                assertThat(result.message()).contains("202", "不用先選");
            }
        }

        long p95Millis = percentile95(elapsedMillis);
        System.out.printf("SYSTEM_PLACE_ACTUAL_ENTRY samples_ms=%s%n", elapsedMillis);
        assertThat(mutationCounts()).isEqualTo(mutationsBefore);
        assertThat(p95Millis).isLessThanOrEqualTo(P95_LIMIT.toMillis());
        System.out.printf(
                "SYSTEM_PLACE_ACTUAL_ENTRY samples=%d p95_ms=%d max_ms=%d business_mutations=0%n",
                SAMPLE_COUNT,
                p95Millis,
                elapsedMillis.stream().mapToLong(Long::longValue).max().orElse(0));
        for (UUID requestId : measuredRequestIds) {
            IntentDecisionTrace trace = traceRepository.findByRequestId(requestId).orElseThrow();
            assertThat(trace.getSelectedCapability()).isEqualTo("ASK_PLACE");
            assertThat(trace.getModel()).isNull();
            assertThat(trace.getInputTokens()).isNull();
            assertThat(trace.getOutputTokens()).isNull();
            assertThat(trace.getStageLatenciesMs()).doesNotContainKey("model");
        }
    }

    @Test
    void logicalSystemPlaceKnowledgeUsesDeterministicRouteWithinLatencyGate() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.TEST, "integration", "place-knowledge-latency");
        Map<String, Long> mutationsBefore = mutationCounts();
        List<Long> elapsedMillis = new ArrayList<>();
        List<UUID> measuredRequestIds = new ArrayList<>();

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            handle(UUID.randomUUID(), "你知道台北車站嗎？");
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                UUID requestId = UUID.randomUUID();
                long startedNanos = System.nanoTime();

                IntentResult result = handle(requestId, "你知道台北車站嗎？");

                elapsedMillis.add(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                measuredRequestIds.add(requestId);
                assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
                assertThat(result.message()).contains("台北火車站", "2 個系統點位")
                        .doesNotContain("安排完整行程");
            }
        }

        long p95Millis = percentile95(elapsedMillis);
        System.out.printf("SYSTEM_PLACE_KNOWLEDGE_ACTUAL_ENTRY samples_ms=%s%n", elapsedMillis);
        assertThat(mutationCounts()).isEqualTo(mutationsBefore);
        assertThat(p95Millis).isLessThanOrEqualTo(P95_LIMIT.toMillis());
        System.out.printf(
                "SYSTEM_PLACE_KNOWLEDGE_ACTUAL_ENTRY samples=%d p95_ms=%d max_ms=%d business_mutations=0%n",
                SAMPLE_COUNT,
                p95Millis,
                elapsedMillis.stream().mapToLong(Long::longValue).max().orElse(0));
        for (UUID requestId : measuredRequestIds) {
            IntentDecisionTrace trace = traceRepository.findByRequestId(requestId).orElseThrow();
            assertThat(trace.getSelectedCapability()).isEqualTo("ASK_PLACE");
            assertThat(trace.getModel()).isNull();
            assertThat(trace.getInputTokens()).isNull();
            assertThat(trace.getOutputTokens()).isNull();
            assertThat(trace.getStageLatenciesMs()).doesNotContainKey("model");
        }
    }

    private IntentResult handle(UUID requestId) {
        return handle(requestId, "今天有什麼事");
    }

    private IntentResult handle(UUID requestId, String text) {
        return RequestCorrelationContext.run(
                requestId, () -> intentService.handle(text, "TEST"));
    }

    private Map<String, Long> mutationCounts() {
        return Map.of(
                "task", count("task"),
                "schedule", count("schedule_item"),
                "reminder", count("reminder"),
                "taskReminder", count("task_reminder_rule"),
                "outbox", count("notification_outbox"),
                "place", count("place"),
                "calendar", count("calendar_plan"));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                WORKSPACE_ID);
    }

    private static long percentile95(List<Long> samples) {
        List<Long> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(sorted.size() * 0.95d) - 1;
        return sorted.get(Math.max(index, 0));
    }
}

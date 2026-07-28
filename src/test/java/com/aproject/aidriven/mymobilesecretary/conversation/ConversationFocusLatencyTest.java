package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusAtomicExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusContributorRegistry;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusIntentExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusIntentHandler;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTargetResolver;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandler;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/** Opt-in deterministic warm and application-cold measurement for core or Project focus. */
@Tag("conversation-focus-latency")
@EnabledIfSystemProperty(named = "conversation.focus.latency.enabled", matches = "true")
@TestMethodOrder(OrderAnnotation.class)
class ConversationFocusLatencyTest extends IntegrationTestBase {

    private static final int WARM_UP_SAMPLES = 5;
    private static final int MEASURED_SAMPLES = 30;
    private static final long LOW_P95_MILLIS = 1_500;
    private static final long MEDIUM_P95_MILLIS = 4_000;
    private static final List<ColdSample> COLD_SAMPLES = Collections.synchronizedList(new ArrayList<>());

    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationFocusIntentHandler focusIntentHandler;
    @Autowired private ConversationFocusTargetResolver targets;
    @Autowired private ConversationFocusContributorRegistry contributors;
    @Autowired private ConversationFocusAtomicExecutor atomicExecutor;
    @Autowired private TaskService taskService;
    @Autowired private ProjectService projectService;
    @Autowired private IntentHandlerRegistry intentHandlers;
    @Autowired private IntentService intentService;
    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void clearColdSamples() {
        COLD_SAMPLES.clear();
    }

    @Test
    @Order(1)
    void coreProfileMeasuresWarmFocusOperationsAndFocusAwareDispatch() {
        if ("project".equals(profile())) {
            measureProjectWarmProfile();
            return;
        }
        assertThat(profile()).isEqualTo("core");
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        Task task;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId, "latency-seed"))) {
            task = taskService.createTask("latency task", null, TaskPriority.NORMAL, null);
        }
        ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(
                new IntentHandlerRegistry(List.of(new TaskFixtureHandler(task))), focusIntentHandler, targets,
                contributors, focusService, atomicExecutor);
        EnumMap<Metric, List<Long>> samples = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            samples.put(metric, new ArrayList<>());
        }

        for (int index = 0; index < WARM_UP_SAMPLES + MEASURED_SAMPLES; index++) {
            runSample(actorId, workspaceId, index, executor,
                    (metric, nanos) -> samples.get(metric).add(nanos));
        }

        samples.forEach((metric, values) -> {
            List<Long> measured = values.subList(WARM_UP_SAMPLES, values.size());
            assertThat(measured).hasSize(MEASURED_SAMPLES);
            assertThat(p95Millis(measured)).as(metric + " warm P95").isLessThanOrEqualTo(LOW_P95_MILLIS);
        });
    }

    @Test
    @Order(10)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldInboundOneUsesFreshContext() {
        measureColdInbound(1, false);
    }

    @Test
    @Order(20)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldInboundTwoUsesFreshContext() {
        measureColdInbound(2, false);
    }

    @Test
    @Order(30)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldInboundThreeUsesFreshContext() {
        measureColdInbound(3, false);
    }

    @Test
    @Order(40)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldInboundFourUsesFreshContext() {
        measureColdInbound(4, false);
    }

    @Test
    @Order(50)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void coldInboundFiveAggregatesIndependentContexts() {
        measureColdInbound(5, true);
    }

    private void runSample(UUID actorId, UUID workspaceId, int index,
                           ConversationFocusIntentExecutor executor,
                           BiConsumer<Metric, Long> recorder) {
        WorkspaceContext context = context(actorId, workspaceId, "latency-" + index);
        String hmac = hmac(index);
        try (WorkspaceContextHolder.Scope scope = WorkspaceContextHolder.open(context)) {
            measure(Metric.ASK, focusService::keep, recorder);
            var first = measure(Metric.ENTER, () -> focusService.enterResource(
                    "TASK", "task:latency-" + index, "latency task", hmac), recorder);
            measure(Metric.SWITCH, () -> focusService.switchResource(
                    "SCHEDULE", "schedule:latency-" + index, "latency schedule", hmac(index + 100)), recorder);
            measure(Metric.EXIT, () -> focusService.exit(hmac(index + 200)), recorder);
            measure(Metric.RESUME, () -> focusService.resume(first.getId(), hmac(index + 300)), recorder);
            measure(Metric.FOCUS_AWARE_DISPATCH, () -> executor.execute("latency", command(),
                    hmac(index + 400)), recorder);
        }
    }

    private void measureProjectWarmProfile() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        Project first;
        Project second;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId, "project-latency-seed"))) {
            first = projectService.createProject(
                    ProjectType.TRAVEL, "大阪 latency", hmac(10_000));
            second = projectService.createProject(
                    ProjectType.TRAVEL, "東京 latency", hmac(10_001));
        }
        ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(
                intentHandlers, focusIntentHandler, targets, contributors, focusService, atomicExecutor);
        EnumMap<Metric, List<Long>> samples = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            samples.put(metric, new ArrayList<>());
        }
        for (int index = 0; index < WARM_UP_SAMPLES + MEASURED_SAMPLES; index++) {
            WorkspaceContext context = context(
                    actorId, workspaceId, "project-latency-" + index);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                int sample = index;
                measure(Metric.ASK, projectService::listProjects,
                        (metric, nanos) -> samples.get(metric).add(nanos));
                measure(Metric.ENTER, () -> executor.execute(
                        "開大阪專案",
                        projectCommand(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, first.getName()),
                        hmac(sample + 20_000)),
                        (metric, nanos) -> samples.get(metric).add(nanos));
                measure(Metric.SWITCH, () -> executor.execute(
                        "切東京專案",
                        projectCommand(IntentCommand.Type.SWITCH_PROJECT_EDIT_MODE, second.getName()),
                        hmac(sample + 30_000)),
                        (metric, nanos) -> samples.get(metric).add(nanos));
                measure(Metric.EXIT, () -> executor.execute(
                        "先關掉專案模式",
                        projectCommand(IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE, null),
                        hmac(sample + 40_000)),
                        (metric, nanos) -> samples.get(metric).add(nanos));
                measure(Metric.RESUME, () -> executor.execute(
                        "回大阪專案",
                        projectCommand(IntentCommand.Type.RESUME_PROJECT_EDIT_MODE, first.getName()),
                        hmac(sample + 50_000)),
                        (metric, nanos) -> samples.get(metric).add(nanos));
                measure(Metric.FOCUS_AWARE_DISPATCH, () -> executor.execute(
                        "看大阪專案",
                        projectCommand(IntentCommand.Type.SHOW_PROJECT_OVERVIEW, first.getName()),
                        hmac(sample + 60_000)),
                        (metric, nanos) -> samples.get(metric).add(nanos));
            }
        }
        samples.forEach((metric, values) -> {
            List<Long> measured = values.subList(WARM_UP_SAMPLES, values.size());
            assertThat(measured).hasSize(MEASURED_SAMPLES);
            assertThat(p95Millis(measured))
                    .as(metric + " Project warm P95")
                    .isLessThanOrEqualTo(LOW_P95_MILLIS);
        });
    }

    private void measureColdInbound(int sample, boolean assertAggregate) {
        assertThat(profile()).isIn("core", "project");
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId, "cold-" + sample))) {
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(taskService.listTasks()).isEmpty();
            boolean projectProfile = "project".equals(profile());
            String title = projectProfile
                    ? "cold project " + sample : "cold focus task " + sample;
            interpreter.nextCommand(projectProfile
                    ? projectCommand(IntentCommand.Type.CREATE_PROJECT, title)
                    : new IntentCommand(IntentCommand.Type.CREATE_TASK, title,
                            null, null, null, null, "NORMAL", null,
                            null, null, null, null, null));
            long started = System.nanoTime();
            IntentResult result = intentService.handle("建立 " + title, "TEST");
            long elapsed = System.nanoTime() - started;

            assertThat(result.action()).isEqualTo(projectProfile
                    ? IntentResult.Action.PROJECT_CREATED
                    : IntentResult.Action.TASK_CREATED);
            assertThat(result.responseEnvelope().message()).contains(title)
                    .doesNotContainPattern(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .doesNotContain("task:");
            assertThat(focusService.activeFocus()).isPresent();
            COLD_SAMPLES.add(new ColdSample(System.identityHashCode(applicationContext), elapsed));
        }
        if (assertAggregate) {
            assertThat(COLD_SAMPLES).hasSize(5);
            assertThat(COLD_SAMPLES.stream().map(ColdSample::contextIdentity).distinct()).hasSize(5);
            assertThat(p95Millis(COLD_SAMPLES.stream().map(ColdSample::elapsedNanos).toList()))
                    .as("five application-cold focus-aware inbound P95")
                    .isLessThanOrEqualTo(MEDIUM_P95_MILLIS);
        }
    }

    private static <T> T measure(Metric metric, java.util.concurrent.Callable<T> action,
                                 BiConsumer<Metric, Long> recorder) {
        long started = System.nanoTime();
        try {
            return action.call();
        } catch (Exception failure) {
            throw new IllegalStateException("latency fixture failed for " + metric, failure);
        } finally {
            recorder.accept(metric, System.nanoTime() - started);
        }
    }

    private static void measure(Metric metric, Runnable action, BiConsumer<Metric, Long> recorder) {
        measure(metric, () -> {
            action.run();
            return null;
        }, recorder);
    }

    private static long p95Millis(List<Long> values) {
        List<Long> ordered = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(ordered.size() * 0.95) - 1;
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(ordered.get(index));
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId, String token) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST, "test", token);
    }

    private static String hmac(int value) {
        return String.format("%064x", value + 1L);
    }

    private static IntentCommand command() {
        return new IntentCommand(IntentCommand.Type.CREATE_TASK, "latency task", null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static IntentCommand projectCommand(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static String profile() {
        return System.getProperty("conversation.focus.latency.profile", "core");
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Latency focus user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Latency focus workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }

    private enum Metric { ASK, ENTER, SWITCH, RESUME, EXIT, FOCUS_AWARE_DISPATCH }

    private record ColdSample(int contextIdentity, long elapsedNanos) {
    }

    private record TaskFixtureHandler(Task task) implements IntentHandler {

        @Override
        public Set<IntentCommand.Type> supportedTypes() {
            return Set.of(IntentCommand.Type.CREATE_TASK);
        }

        @Override
        public IntentResult handle(String text, IntentCommand command) {
            return new IntentResult(IntentResult.Action.TASK_CREATED, "已建立", task, null);
        }
    }
}

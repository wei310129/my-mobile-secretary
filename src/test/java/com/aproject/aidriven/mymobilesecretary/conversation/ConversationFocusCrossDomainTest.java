package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusAtomicExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusContributorRegistry;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusIntentExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusIntentHandler;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTargetResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusResponseEnvelope;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandler;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusCrossDomainTest extends IntegrationTestBase {

    @Autowired private ConversationFocusIntentHandler focusIntentHandler;
    @Autowired private ConversationFocusTargetResolver targets;
    @Autowired private ConversationFocusContributorRegistry contributors;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationFocusAtomicExecutor atomicExecutor;
    @Autowired private ConversationFocusQuoteResolver quoteResolver;
    @Autowired private IntentHandlerRegistry intentHandlers;
    @Autowired private TaskService taskService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private TravelItineraryDraftService travelItineraryDraftService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void taskScheduleOneShotQuoteFeedbackAndReplayStayScopedAndDeterministic() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId, "cross domain user");
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            Task task = taskService.createTask("繳電費", null, TaskPriority.NORMAL, null);
            ScheduleDecision schedule = scheduleService.createSchedule("醫院回診",
                    Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"), null);
            ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(
                    new IntentHandlerRegistry(java.util.List.of(new FixtureHandler(task, schedule))),
                    focusIntentHandler, targets, contributors, focusService, atomicExecutor);

            IntentResult taskReply = executor.execute("建立待辦", command(IntentCommand.Type.CREATE_TASK),
                    "a".repeat(64));
            ConversationFocus taskFocus = focusService.activeFocus().orElseThrow();
            assertThat(taskReply.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
            assertThat(taskReply.responseEnvelope().message()).contains("目前先處理「繳電費」")
                    .doesNotContain("task:").doesNotContain(task.getId().toString());
            assertThat(taskFocus.getRootDomain()).isEqualTo("TASK");
            assertThat(taskFocus.getRoutingKey()).isEqualTo("task:" + task.getId());

            IntentResult scheduleReply = executor.execute("改成回診", command(IntentCommand.Type.RESCHEDULE_SCHEDULE),
                    "b".repeat(64));
            ConversationFocus scheduleFocus = focusService.activeFocus().orElseThrow();
            assertThat(scheduleReply.responseEnvelope().message()).contains("先暫離「繳電費」，改處理「醫院回診」")
                    .doesNotContain("schedule:").doesNotContain(schedule.item().getId().toString());
            assertThat(scheduleFocus.getRootDomain()).isEqualTo("SCHEDULE");
            assertThat(scheduleFocus.getRoutingKey()).isEqualTo("schedule:" + schedule.item().getId());

            executor.execute("天氣", command(IntentCommand.Type.ASK_WEATHER), "c".repeat(64));
            executor.execute("這個回覆不對", command(IntentCommand.Type.FEEDBACK), "d".repeat(64));
            assertThat(focusService.activeFocus().orElseThrow().getId()).isEqualTo(scheduleFocus.getId());
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);

            focusService.exit("e".repeat(64));
            var quoted = quoteResolver.resolveSuspendedFocus(taskFocus.getId());
            FocusResponseEnvelope resumed = atomicExecutor.execute(quoted.decision(), quoted.control(),
                    "f".repeat(64), quoted.notice(),
                    () -> FocusResponseEnvelope.withoutNotice("已依引用回到原待辦"));
            assertThat(resumed.message()).contains("繼續處理「繳電費」");
            assertThat(focusService.activeFocus().orElseThrow().getId()).isEqualTo(taskFocus.getId());

            executor.execute("重播的回診操作", command(IntentCommand.Type.RESCHEDULE_SCHEDULE),
                    "b".repeat(64));
            assertThat(transitionCount(workspaceId)).isEqualTo(4L);
            assertThat(focusService.activeFocus().orElseThrow().getId()).isEqualTo(taskFocus.getId());

            executor.execute("這則是回饋", command(IntentCommand.Type.FEEDBACK), "9".repeat(64));
            assertThat(focusService.activeFocus().orElseThrow().getId()).isEqualTo(taskFocus.getId());
            focusService.exit("0".repeat(64));
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(transitionCount(workspaceId)).isEqualTo(5L);
            assertThat(taskService.getTask(task.getId()).getTitle()).isEqualTo("繳電費");
            assertThat(scheduleService.getSchedule(schedule.item().getId()).getTitle()).isEqualTo("醫院回診");
        }
    }

    @Test
    void unavailableContributorTargetRollsBackHandlerMutationAndFocusWrite() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId, "invalid target user");
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
        Task unavailable = mock(Task.class);
        when(unavailable.getId()).thenReturn(999_999L);
        when(unavailable.getTitle()).thenReturn("不存在的待辦");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(
                    new IntentHandlerRegistry(java.util.List.of(
                            new UnavailableTargetHandler(jdbcTemplate, unavailable, workspaceId, actorId))),
                    focusIntentHandler, targets, contributors, focusService, atomicExecutor);

            assertThatThrownBy(() -> executor.execute("建立待辦", command(IntentCommand.Type.CREATE_TASK),
                    "z".repeat(64))).isInstanceOf(IllegalStateException.class)
                    .hasMessage("focus target is unavailable");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM item WHERE name = 'rolled back target'",
                Long.class)).isZero();
        assertThat(transitionCount(workspaceId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_focus WHERE workspace_id = ?", Long.class, workspaceId))
                .isZero();
    }

    @Test
    void itineraryDraftConfirmationUsesActualHandlerAndKeepsExistingTaskFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId, "draft confirmation user");
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            Task task = taskService.createTask("整理護照", null, TaskPriority.NORMAL, null);
            ConversationFocus taskFocus = focusService.enterResource("TASK", "task:" + task.getId(),
                    task.getTitle(), "7".repeat(64));
            travelItineraryDraftService.create(new ReceiptCommand(null, null, java.util.List.of(),
                    ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "北海道行程表",
                    java.util.List.of(new ReceiptCommand.ItineraryEntry(
                            "08-01", "09:00", "10:00", "集合", "新千歲機場", "集合")),
                    java.util.List.of(), java.util.List.of("護照隨身攜帶")));
            ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(intentHandlers,
                    focusIntentHandler, targets, contributors, focusService, atomicExecutor);

            IntentResult reply = executor.execute("確認匯入", command(
                    IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT), "8".repeat(64));

            assertThat(reply.action()).isEqualTo(IntentResult.Action.TRAVEL_ITINERARY_CONFIRMED);
            assertThat(reply.focusNotice()).isNull();
            assertThat(focusService.activeFocus().orElseThrow().getId()).isEqualTo(taskFocus.getId());
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
        }
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, "fixture", null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private long transitionCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private void seedAccount(UUID actorId, UUID workspaceId, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, displayName);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Cross domain workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }

    private record FixtureHandler(Task task, ScheduleDecision schedule) implements IntentHandler {

        @Override
        public Set<IntentCommand.Type> supportedTypes() {
            return Set.of(IntentCommand.Type.CREATE_TASK, IntentCommand.Type.RESCHEDULE_SCHEDULE,
                    IntentCommand.Type.ASK_WEATHER, IntentCommand.Type.FEEDBACK);
        }

        @Override
        public IntentResult handle(String text, IntentCommand command) {
            return switch (command.type()) {
                case CREATE_TASK -> new IntentResult(IntentResult.Action.TASK_CREATED, "已建立", task, null);
                case RESCHEDULE_SCHEDULE -> IntentResult.scheduleMessage(
                        IntentResult.Action.SCHEDULE_RESCHEDULED, "已改期", schedule);
                case ASK_WEATHER -> IntentResult.message(IntentResult.Action.WEATHER_INFO, "晴天");
                case FEEDBACK -> IntentResult.feedbackReceived();
                default -> throw new IllegalArgumentException("unsupported fixture command");
            };
        }
    }

    private record UnavailableTargetHandler(JdbcTemplate jdbcTemplate, Task task, UUID workspaceId,
                                            UUID actorId) implements IntentHandler {

        @Override
        public Set<IntentCommand.Type> supportedTypes() {
            return Set.of(IntentCommand.Type.CREATE_TASK);
        }

        @Override
        public IntentResult handle(String text, IntentCommand command) {
            jdbcTemplate.update("""
                    INSERT INTO item (
                        name, created_at, inventory_quantity, shopping_needed, updated_at,
                        workspace_id, created_by_user_id)
                    VALUES ('rolled back target', CURRENT_TIMESTAMP, 0, FALSE, CURRENT_TIMESTAMP, ?, ?)
                    """, workspaceId, actorId);
            return new IntentResult(IntentResult.Action.TASK_CREATED, "已建立", task, null);
        }
    }
}

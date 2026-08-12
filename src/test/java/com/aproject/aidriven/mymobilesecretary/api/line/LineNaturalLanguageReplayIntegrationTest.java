package com.aproject.aidriven.mymobilesecretary.api.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogRepository;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentIssueRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 去識別化 LINE 多輪重播：只保留歷史風險形狀，不複製任何實際使用者原話或識別資訊。
 */
class LineNaturalLanguageReplayIntegrationTest extends IntegrationTestBase {

    private static final String TEST_SECRET = "test-channel-secret";
    private static final String OWNER_USER_ID = "test-owner-user";
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int LATENCY_WARMUP_COUNT = 60;
    private static final int LATENCY_SAMPLE_COUNT = 60;

    @Autowired
    private StubIntentInterpreter interpreter;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private ScheduleService schedules;
    @Autowired
    private LineMessageLogRepository messages;
    @Autowired
    private IntentIssueRepository issues;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private LineMessagingClient messagingClient;

    @Test
    void signedLineHelpConversationStaysDeterministicAcrossThreeTurns() throws Exception {
        sendText("你現在能協助哪些事情");
        assertThat(latestReply()).contains("1. 待辦與任務", "2. 行程與空檔", "請選一類");

        sendText("第2類");
        assertThat(latestReply()).contains("行程建立範例", "修改、刪除或查詢");

        sendText("我想看查詢");
        assertThat(latestReply()).contains("行程查詢範例", "週末")
                .doesNotContain("CAPABILITY_HELP", "UUID", "null");
    }

    @Test
    void correctionInvalidatesAnOldOrdinalListBeforeDestructiveFollowUp() throws Exception {
        sendInterpreted("提醒我整理紙本帳單",
                command(IntentCommand.Type.CREATE_TASK, "整理紙本帳單",
                        "提醒我整理紙本帳單", null));
        sendInterpreted("提醒我更新緊急聯絡卡",
                command(IntentCommand.Type.CREATE_TASK, "更新緊急聯絡卡",
                        "提醒我更新緊急聯絡卡", null));
        sendInterpreted("列出我目前的待辦",
                command(IntentCommand.Type.LIST_TASKS, null, "列出我目前的待辦", null));
        assertThat(latestReply()).contains("整理紙本帳單", "更新緊急聯絡卡");

        sendText("你沒聽懂，我是在更正你的回答方式");
        assertThat(latestReply()).contains("理解錯了");

        sendInterpreted("取消第二個待辦",
                command(IntentCommand.Type.CANCEL_TASK, null, "取消第二個待辦",
                        ordinalOptions(2)));

        assertThat(latestReply()).contains("目前沒有可指代的待辦", "先列出待辦");
        assertThat(tasks.findAll()).extracting(task -> task.getStatus())
                .containsOnly(TaskStatus.CREATED);
    }

    @Test
    void ungroundedAndQuotedCommandsCannotCreateBusinessData() throws Exception {
        sendInterpreted("今天只想記錄一下心情",
                command(IntentCommand.Type.CREATE_TASK, "替不存在的寵物買飼料",
                        "替不存在的寵物買飼料", null));
        assertThat(tasks.findAll()).isEmpty();
        assertThat(latestReply()).contains("無法對應", "不會執行");

        String quotedMessageId = "quoted-" + UUID.randomUUID();
        sendInterpreted("參考備註是取消社區年費待辦", quotedMessageId, null,
                command(IntentCommand.Type.UNKNOWN, null,
                        "參考備註是取消社區年費待辦", null));
        sendInterpreted("照這則內容處理", "follow-" + UUID.randomUUID(),
                "message-" + quotedMessageId,
                command(IntentCommand.Type.CREATE_TASK, "取消社區年費待辦",
                        "取消社區年費待辦", null));

        assertThat(tasks.findAll()).isEmpty();
        assertThat(latestReply()).contains("無法對應", "不會執行")
                .doesNotContain(ACTOR_ID.toString(), WORKSPACE_ID.toString());
    }

    @Test
    void feedbackNegationIsRecordedWithoutClaimingAFixOrMutatingTasks() throws Exception {
        sendText("不是重複建立，而是你不應該亂回答");

        assertThat(latestReply())
                .contains("不是在說重複建立", "不會建立或修改", "不會說成已經修好")
                .doesNotContain("已修復", "DUPLICATE", "FALLBACK");
        assertThat(tasks.findAll()).isEmpty();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            assertThat(issues.findAllByOrderByCreatedAtDesc())
                    .extracting(IntentIssue::getCategory)
                    .contains(IntentIssue.Category.FEEDBACK);
        }
    }

    @Test
    void signedLineCompoundScheduleAnalysisIsTruthfulAndReadOnly() throws Exception {
        long schedulesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM schedule_item", Long.class);
        interpreter.nextCommands(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "排得最滿的日子", IntentOptions.empty()),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "耗時最久的活動", IntentOptions.empty()));

        sendText("請找出下週排得最滿的日子和耗時最久的活動，"
                + "還要說明該活動之前與之後各空多久");

        assertThat(latestReply())
                .contains("目前還不能完整處理", "指定行程前後的相鄰空檔", "不會建立或修改資料")
                .doesNotContain("ASK_", "Intent", ACTOR_ID.toString(), WORKSPACE_ID.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_item", Long.class))
                .isEqualTo(schedulesBefore);
    }

    @Test
    void correctionStillBypassesTheModelAndLifeRecordWhenTheInterpreterWouldFail()
            throws Exception {
        interpreter.clear();

        sendText("我是在指出你上一則回答有錯，不是要建立任何資料");

        assertThat(latestReply()).contains("理解錯了", "不會建立")
                .doesNotContain("AI", "暫時無法", "UUID");
        assertThat(countLifeRecords("USER_UTTERANCE")).isZero();
        assertThat(tasks.findAll()).isEmpty();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            assertThat(issues.findAllByOrderByCreatedAtDesc())
                    .extracting(IntentIssue::getCategory)
                    .containsExactly(IntentIssue.Category.FEEDBACK);
        }
    }

    @Test
    void feedbackBatchCannotBecomeAUserLifeRecordOrUnrelatedClarification()
            throws Exception {
        String text = "你上一則回答錯了，而且不應該再補上無關內容";
        interpreter.nextCommands(
                command(IntentCommand.Type.FEEDBACK, null, text, null),
                command(IntentCommand.Type.UNKNOWN, null, "不應該再補上無關內容", null));

        send(text, "event-" + UUID.randomUUID(), null);

        assertThat(latestReply()).contains("收到").doesNotContain("有一項", "接送", "UUID");
        assertThat(countLifeRecords("USER_UTTERANCE")).isZero();
        assertThat(tasks.findAll()).isEmpty();
    }

    @Test
    void signedLineCaregivingTransportCreatesAPointReminderNotAClassInterval()
            throws Exception {
        LocalDate tomorrow = LocalDate.now(clock.withZone(TAIPEI)).plusDays(1);
        String source = "明天上午九點送孩子去課後班";
        interpreter.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "送孩子去課後班",
                null,
                ZonedDateTime.of(tomorrow, LocalTime.of(9, 0), TAIPEI)
                        .toOffsetDateTime()
                        .toString(),
                ZonedDateTime.of(tomorrow, LocalTime.of(10, 0), TAIPEI)
                        .toOffsetDateTime()
                        .toString(),
                null,
                "NORMAL",
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                source));

        send(source, "event-" + UUID.randomUUID(), null);

        assertThat(latestReply()).contains("送孩子去課後班").doesNotContain("10:00");
        assertThat(tasks.findAll()).singleElement()
                .satisfies(task -> assertThat(task.getDueAt())
                        .isEqualTo(at(tomorrow, LocalTime.of(9, 0))));
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            assertThat(schedules.listSchedules(null)).isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                            Long.class,
                            WORKSPACE_ID))
                    .isZero();
        }
    }

    @Test
    void signedLineWeekdayTransportKeepsARecurringPointReminder() throws Exception {
        LocalDate firstMonday = LocalDate.now(clock.withZone(TAIPEI))
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        String source = "每個平日上午九點送小明去上課";
        interpreter.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "送小明去上課",
                null,
                ZonedDateTime.of(firstMonday, LocalTime.of(9, 0), TAIPEI)
                        .toOffsetDateTime().toString(),
                ZonedDateTime.of(firstMonday, LocalTime.of(10, 0), TAIPEI)
                        .toOffsetDateTime().toString(),
                null, "NORMAL", null, null, null, null, null, true,
                recurrenceOptions("WEEKDAYS"), source));

        send(source, "event-" + UUID.randomUUID(), null);

        assertThat(tasks.findAll()).singleElement().satisfies(task -> {
            assertThat(task.getDueAt()).isEqualTo(at(firstMonday, LocalTime.of(9, 0)));
            assertThat(task.getRecurrence()).isEqualTo(Task.Recurrence.WEEKDAYS);
        });
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            assertThat(schedules.listSchedules(null)).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                    Long.class, WORKSPACE_ID)).isZero();
        }
    }

    @Test
    void weekendOverviewUsesOneSignedLineTurnAndKeepsDaysSeparate() throws Exception {
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate saturday = monday.plusDays(5);
        LocalDate sunday = saturday.plusDays(1);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            schedules.createSchedule("週六社區活動",
                    at(saturday, LocalTime.of(10, 0)),
                    at(saturday, LocalTime.of(11, 0)), null, false);
            schedules.createSchedule("週日整理房間",
                    at(sunday, LocalTime.of(15, 0)),
                    at(sunday, LocalTime.of(16, 0)), null, false);
        }

        sendText("這週末有哪些行程");

        assertThat(latestReply())
                .contains(saturday.toString().replace("-", "/"), "週六社區活動")
                .contains(sunday.toString().replace("-", "/"), "週日整理房間")
                .doesNotContain("合併成同一天", "SCHEDULES_LISTED", "null");
    }

    @Test
    void deterministicLineRepairRoutesMeetWarmP95LatencyBudget() throws Exception {
        List<String> turns = List.of(
                "你可以幫哪些忙",
                "不是重複建立，而是你不應該亂回答",
                "這週末有哪些行程",
                "你現在能協助哪些事情",
                "並非重複項目，我是在說你答錯問題",
                "這個周末行程排了哪些");
        for (int warmup = 0; warmup < LATENCY_WARMUP_COUNT; warmup++) {
            String turn = turns.get(warmup % turns.size());
            sendText(turn);
            assertThat(latestReply()).doesNotContain("暫時無法處理", "CAPABILITY_HELP", "null");
        }

        List<Long> elapsedMillis = new ArrayList<>();
        for (int sample = 0; sample < LATENCY_SAMPLE_COUNT; sample++) {
            String turn = turns.get(sample % turns.size());
            long started = System.nanoTime();
            sendText(turn);
            elapsedMillis.add((System.nanoTime() - started) / 1_000_000);
            assertThat(latestReply())
                    .doesNotContain("暫時無法處理", "CAPABILITY_HELP", "null");
        }

        System.out.printf("LINE_REPAIR_SAMPLES_MS=%s%n", elapsedMillis);
        List<Long> ordered = elapsedMillis.stream().sorted(Comparator.naturalOrder()).toList();
        long p95 = ordered.get((int) Math.ceil(ordered.size() * 0.95) - 1);
        System.out.printf("LINE_REPAIR_P95_MS=%d%n", p95);
        verify(messagingClient, times(LATENCY_WARMUP_COUNT + elapsedMillis.size()))
                .reply(anyString(), anyString());
        assertThat(p95).as("signed LINE deterministic warm P95 milliseconds")
                .isLessThanOrEqualTo(1_500L);
    }

    private void sendText(String text) throws Exception {
        send(text, "event-" + UUID.randomUUID(), null);
    }

    private void sendInterpreted(String text, IntentCommand command) throws Exception {
        sendInterpreted(text, "event-" + UUID.randomUUID(), null, command);
    }

    private void sendInterpreted(
            String text, String eventId, String quotedMessageId, IntentCommand command)
            throws Exception {
        interpreter.nextCommand(command);
        send(text, eventId, quotedMessageId);
    }

    private void send(String text, String eventId, String quotedMessageId) throws Exception {
        byte[] body = textMessageEvent(text, eventId, quotedMessageId);
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

    private byte[] textMessageEvent(String text, String eventId, String quotedMessageId) {
        String quoted = quotedMessageId == null
                ? ""
                : ",\"quotedMessageId\":\"%s\"".formatted(quotedMessageId);
        return """
                {"events":[{"type":"message","replyToken":"rt-%s","webhookEventId":"%s",\
                "source":{"userId":"%s"},\
                "message":{"id":"message-%s","type":"text","text":"%s"%s}}]}
                """.formatted(eventId, eventId, OWNER_USER_ID, eventId, text, quoted)
                .getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private static IntentCommand command(
            IntentCommand.Type type, String title, String sourceText, IntentOptions options) {
        return new IntentCommand(type, title, null, null, null, null, "NORMAL", null,
                null, null, null, null, false, options, sourceText);
    }

    private static IntentOptions ordinalOptions(int ordinal) {
        return new IntentOptions(null, ordinal, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private static IntentOptions recurrenceOptions(String recurrence) {
        return new IntentOptions(null, null, null, null, null, null, recurrence, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private static WorkspaceContext lineContext() {
        return new WorkspaceContext(ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.LINE,
                "line", "user:" + OWNER_USER_ID);
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, TAIPEI).toInstant();
    }

    private long countLifeRecords(String recordType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM tagged_life_record WHERE record_type = ?",
                Long.class,
                recordType);
    }
}

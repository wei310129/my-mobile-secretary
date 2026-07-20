package com.aproject.aidriven.mymobilesecretary.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.event.application.EventIntakeService.DraftPayload;
import com.aproject.aidriven.mymobilesecretary.event.domain.EventIntakeDraft;
import com.aproject.aidriven.mymobilesecretary.event.persistence.EventIntakeDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private EventIntakeDraftRepository repository;
    private ObjectMapper objectMapper;
    private ScheduleService scheduleService;
    private PlaceService placeService;
    private EventIntakeService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventIntakeDraftRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        scheduleService = mock(ScheduleService.class);
        placeService = mock(PlaceService.class);
        service = new EventIntakeService(repository, objectMapper,
                scheduleService, placeService,
                mock(TaskService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void announcementExtractsEventDateAndPlaceButNeverUsesTicketWindowAsEventTime() {
        String announcement = """
                🔥 敲破碗！JCConf 2026 門票正式開賣！
                📅 活動日期：2026 年 9 月 11 日 (週五)
                📍 活動地點：臺大醫院國際會議中心
                📅 售票時間：2026/06/25 00:00 - 2026/09/10 12:00
                """;

        DraftPayload parsed = EventIntakeService.parseAnnouncement(announcement);

        assertThat(parsed.title()).isEqualTo("JCConf 2026");
        assertThat(parsed.date()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(parsed.placeName()).isEqualTo("臺大醫院國際會議中心");
        assertThat(parsed.startTime()).isNull();
        assertThat(parsed.endTime()).isNull();
    }

    @Test
    void announcementIsSavedAsDraftWithoutCreatingSchedule() {
        AtomicBoolean mutation = new AtomicBoolean();
        IntentResult result = service.answer("""
                JCConf 2026 門票正式開賣
                活動日期：2026年9月11日
                活動地點：臺大醫院國際會議中心
                """, () -> mutation.set(true)).orElseThrow();

        assertThat(mutation).isTrue();
        assertThat(result.message()).contains("活動草稿", "尚未建立行程", "JCConf 2026",
                "2026/09/11（五）", "臺大醫院國際會議中心", "時間｜待補");
        verify(repository).save(any(EventIntakeDraft.class));
    }

    @Test
    void ticketFollowUpKeepsKnownFieldsAndAsksOnlyForMissingEventTime() throws Exception {
        DraftPayload payload = new DraftPayload("JCConf 2026", LocalDate.of(2026, 9, 11),
                null, null, "臺大醫院國際會議中心", null,
                null, null, null, false, "TEXT", "source");
        EventIntakeDraft draft = EventIntakeDraft.create("JCConf 2026",
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        IntentResult result = inScope(() -> service.answer(
                "我已經訂好票了，幫我加入行程", () -> { }).orElseThrow());

        assertThat(result.message()).contains("活動日期已經有了", "缺少實際開始與結束時間",
                "直接告訴我時間", "貼官方活動網頁", "明確說要我查", "不會建立行程");
        DraftPayload updated = objectMapper.readValue(draft.getPayload(), DraftPayload.class);
        assertThat(updated.addRequested()).isTrue();
        assertThat(updated.date()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void suppliedTimeAdvancesToPlaceConfirmationOneStepAtATime() throws Exception {
        DraftPayload payload = new DraftPayload("JCConf 2026", LocalDate.of(2026, 9, 11),
                null, null, "臺大醫院國際會議中心", null,
                null, null, null, true, "TEXT", "source");
        EventIntakeDraft draft = EventIntakeDraft.create("JCConf 2026",
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        IntentResult result = inScope(() -> service.answer(
                "活動時間是9:00到17:00", () -> { }).orElseThrow());

        assertThat(result.message()).contains("活動地點是「臺大醫院國際會議中心」嗎",
                "地點正確", "更精確地址");
    }

    @Test
    void shortDateCompletesPendingImageDraftUsingCurrentTaipeiYear() throws Exception {
        DraftPayload payload = new DraftPayload("開發者實作工作坊", null,
                java.time.LocalTime.of(15, 55), java.time.LocalTime.of(17, 10),
                "701E", null, null, null, null, false, "IMAGE", "source");
        EventIntakeDraft draft = EventIntakeDraft.create(payload.title(),
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        IntentResult result = inScope(() -> service.answer("""
                【近期對話】
                助理：請告訴我活動日期
                【使用者目前訊息】7/9
                """, () -> { }).orElseThrow());

        DraftPayload updated = objectMapper.readValue(draft.getPayload(), DraftPayload.class);
        assertThat(updated.date()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(result.message()).contains("2026/07/09（四）", "活動地點是「701E」嗎");
    }

    @Test
    void independentTimedReminderIsNotConsumedByPendingEventDraft() throws Exception {
        DraftPayload payload = new DraftPayload("JCConf 2026", LocalDate.of(2026, 9, 11),
                null, null, "臺大醫院國際會議中心", null,
                null, null, null, false, "TEXT", "source");
        EventIntakeDraft draft = EventIntakeDraft.create("JCConf 2026",
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        Optional<IntentResult> result = inScope(() -> service.answer(
                "明天下午三點提醒我要告訴你確切時間", () -> { }));

        assertThat(result).isEmpty();
        assertThat(objectMapper.readValue(draft.getPayload(), DraftPayload.class))
                .isEqualTo(payload);
        verify(repository, never()).save(any(EventIntakeDraft.class));
    }

    @Test
    void unrelatedLocationQuestionIsNotConsumedByPendingEventDraft() throws Exception {
        DraftPayload payload = new DraftPayload("開發者實作工作坊", LocalDate.of(2026, 10, 11),
                java.time.LocalTime.of(15, 55), java.time.LocalTime.of(17, 10),
                "701E", true, null, null, null, true, "IMAGE", "source");
        EventIntakeDraft draft = EventIntakeDraft.create(payload.title(),
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        Optional<IntentResult> result = inScope(() -> service.answer("刷油漆的地點？", () -> { }));

        assertThat(result).isEmpty();
        assertThat(objectMapper.readValue(draft.getPayload(), DraftPayload.class))
                .isEqualTo(payload);
    }

    @Test
    void explicitLineQuoteCanContinueTheMatchingDraft() throws Exception {
        DraftPayload payload = new DraftPayload("開發者實作工作坊", LocalDate.of(2026, 10, 11),
                java.time.LocalTime.of(15, 55), java.time.LocalTime.of(17, 10),
                "701E", true, null, null, null, true, "IMAGE", "source");
        EventIntakeDraft draft = EventIntakeDraft.create(payload.title(),
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        IntentResult result = inScope(() -> service.answer("""
                【LINE 明確引用】活動草稿「開發者實作工作坊」需要提早到場嗎？
                【使用者目前訊息】不用提早
                """, () -> { }).orElseThrow());

        DraftPayload updated = objectMapper.readValue(draft.getPayload(), DraftPayload.class);
        assertThat(updated.arrivalLeadMinutes()).isZero();
        assertThat(result.message()).contains("需要在活動開始前多久提醒");
    }

    @Test
    void reminderFollowUpWithoutMinutesStaysNullableInsteadOfThrowing() throws Exception {
        DraftPayload payload = new DraftPayload("JCConf 2026", LocalDate.of(2026, 9, 11),
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0),
                "臺大醫院國際會議中心", true, 0, null, null, true, "TEXT", "source");
        EventIntakeDraft draft = EventIntakeDraft.create(payload.title(),
                objectMapper.writeValueAsString(payload), NOW.plusSeconds(3600), NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(draft));

        IntentResult result = inScope(() -> service.answer(
                "這個活動要提醒我", () -> { }).orElseThrow());

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("需要在活動開始前多久提醒");
    }

    @Test
    void completeRegistrationCreatesOneCalendarEntryAndDoesNotDuplicateIt() throws Exception {
        Instant start = Instant.parse("2026-09-11T01:00:00Z");
        Instant end = Instant.parse("2026-09-11T09:00:00Z");
        ScheduleItem created = ScheduleItem.propose("JCConf 2026", start, end, null, NOW);
        when(scheduleService.listSchedules(null)).thenReturn(java.util.List.of())
                .thenReturn(java.util.List.of(created));
        when(scheduleService.createSchedule(eq("JCConf 2026"), eq(start), eq(end),
                eq(null), eq(ScheduleItem.Recurrence.NONE)))
                .thenReturn(new ScheduleDecision(created, null));

        IntentResult first = service.ingestRegisteredEvent("JCConf 2026", "2026-09-11",
                "09:00", "17:00", null, "registration screenshot", () -> { });
        IntentResult duplicate = service.ingestRegisteredEvent("JCConf 2026", "2026-09-11",
                "09:00", "17:00", null, "registration screenshot", () -> { });

        assertThat(first.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
        assertThat(first.message()).contains("加入行事曆", "2026/09/11（五）", "09:00–17:00");
        assertThat(duplicate.message()).contains("已存在", "不會重複建立");
        verify(scheduleService, times(1)).createSchedule(
                "JCConf 2026", start, end, null, ScheduleItem.Recurrence.NONE);
    }

    @Test
    void incompleteRegistrationIsSavedAndAsksOnlyForMissingDate() {
        IntentResult result = service.ingestRegisteredEvent("JCConf 2026", null,
                "09:00", "17:00", "臺大醫院國際會議中心",
                "registration screenshot", () -> { });

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("報名成功", "請告訴我活動日期");
        verify(repository).save(any(EventIntakeDraft.class));
        verify(scheduleService, never()).createSchedule(any(), any(), any(), any(), any());
        verify(placeService, never()).createPlace(any(), any(), any(), any(), any());
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) throws Exception {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST))) {
            return action.call();
        }
    }
}

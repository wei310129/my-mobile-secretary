package com.aproject.aidriven.mymobilesecretary.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private EventIntakeService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventIntakeDraftRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new EventIntakeService(repository, objectMapper,
                mock(ScheduleService.class), mock(PlaceService.class),
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

    private static <T> T inScope(java.util.concurrent.Callable<T> action) throws Exception {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST))) {
            return action.call();
        }
    }
}

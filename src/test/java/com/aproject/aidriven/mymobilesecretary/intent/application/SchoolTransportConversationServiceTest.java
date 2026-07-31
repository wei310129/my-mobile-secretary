package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.domain.SchoolTransportDraft;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.SchoolTransportDraftRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchoolTransportConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final WorkspaceContext SCOPE = new WorkspaceContext(
            UUID.fromString("10000000-0000-0000-0000-000000000101"),
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            WorkspaceChannel.LINE);

    @Mock
    private SchoolTransportDraftRepository repository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ConversationContextService conversationContext;

    private AtomicReference<SchoolTransportDraft> pending;
    private CopyOnWriteArrayList<ScheduleItem> schedules;
    private SchoolTransportConversationService service;

    @BeforeEach
    void setUp() {
        pending = new AtomicReference<>();
        schedules = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.lenient().when(scheduleService.listSchedules(isNull()))
                .thenAnswer(call -> java.util.List.copyOf(schedules));
        when(repository.findFirstByWorkspaceIdAndCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), eq(SchoolTransportDraft.Status.PENDING), any()))
                .thenAnswer(call -> Optional.ofNullable(pending.get())
                        .filter(draft -> draft.getStatus() == SchoolTransportDraft.Status.PENDING));
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(call -> {
            SchoolTransportDraft draft = call.getArgument(0);
            pending.set(draft);
            return draft;
        });
        org.mockito.Mockito.lenient().when(scheduleService.createFamilySchedule(
                anyString(), any(), any(), isNull(), anyString(),
                eq(ScheduleItem.Recurrence.WEEKLY), any()))
                .thenAnswer(call -> decision(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        org.mockito.Mockito.lenient().when(scheduleService.createSchedule(
                anyString(), any(), any(), isNull(), eq(ScheduleItem.Recurrence.WEEKLY), any(),
                eq(ScheduleItem.Category.FAMILY)))
                .thenAnswer(call -> decision(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        service = new SchoolTransportConversationService(
                repository, scheduleService, new ObjectMapper().findAndRegisterModules(), CLOCK);
        service.setConversationContext(conversationContext);
    }

    @Test
    void realTranscriptKeepsKnownFieldsAndCompletesFromOneNaturalFollowUp() {
        AtomicInteger mutations = new AtomicInteger();

        IntentResult first = answer(
                "我女兒到9月底以前的每週六10-12點都要去上夏恩英語（新店七張分校），"
                        + "我會負責從我家出發接女兒到夏恩英語（新店七張分校），12點也要負責去接，"
                        + "出發地就看當時我人在哪",
                mutations).orElseThrow();

        assertThat(first.message())
                .contains("我已記住", "每週六", "10:00–12:00", "送去：我", "接回：我")
                .contains("送去預計幾點出發", "12:00 從哪裡接", "接回行程預計幾點結束")
                .doesNotContain("誰負責接回");

        IntentResult completed = answer(
                "9:30 從我家出發，就在夏恩英語（新店七張分校）接，12:30 結束",
                mutations).orElseThrow();

        assertCompleted(completed);
        assertThat(mutations).hasValue(2);
    }

    @Test
    void completeDetailedSentenceCreatesTheWholeFlowInOneTurn() {
        IntentResult result = answer(
                "到9月底以前，每週六我9:30從我家出發送女兒去上夏恩英語，10-12點上課，"
                        + "12點我在夏恩英語接，12:30結束",
                new AtomicInteger()).orElseThrow();

        assertCompleted(result);
    }

    @Test
    void simpleDropOffReminderDoesNotOpenAWholeCourseAndPickupDraft() {
        Optional<IntentResult> result = answer(
                "每週六10-12點送女兒去上英語課",
                new AtomicInteger());

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
        verifyNoInteractions(scheduleService);
    }

    @Test
    void terseRepliesCanFillOneMissingFieldAtATimeWithoutRepeatingKnownAnswers() {
        AtomicInteger mutations = new AtomicInteger();
        answer("女兒每週六10-12點要上夏恩英語，到9月底，我負責送也負責接，從家裡出發", mutations)
                .orElseThrow();

        IntentResult afterDeparture = answer("9:30出發", mutations).orElseThrow();
        assertThat(afterDeparture.message())
                .doesNotContain("送去預計幾點出發")
                .contains("12:00 從哪裡接", "接回行程預計幾點結束");

        IntentResult afterPlace = answer("就在夏恩英語接", mutations).orElseThrow();
        assertThat(afterPlace.message())
                .doesNotContain("從哪裡接")
                .contains("接回行程預計幾點結束");

        assertCompleted(answer("12:30結束", mutations).orElseThrow());
        assertThat(mutations).hasValue(4);
    }

    @Test
    void colloquialSentenceStillCreatesThreePreciselyTimedSchedules() {
        IntentResult result = answer(
                "女兒每禮拜六都在夏恩英語上課10-12點，做到9月底；"
                        + "我9點半從家裡出發送她，12點我去夏恩英語接，12點半到家",
                new AtomicInteger()).orElseThrow();

        assertCompleted(result);
    }

    @Test
    void resendingTheSameNaturalRequestDoesNotCreateAnotherFlow() {
        String text = "到9月底以前，每週六我9:30從我家出發送女兒去上夏恩英語，10-12點上課，"
                + "12點我在夏恩英語接，12:30結束";
        assertCompleted(answer(text, new AtomicInteger()).orElseThrow());

        IntentResult duplicate = answer(text, new AtomicInteger()).orElseThrow();

        assertThat(duplicate.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
        assertThat(duplicate.message()).contains("已存在", "沒有重複建立");
        verify(scheduleService, times(1)).createFamilySchedule(
                anyString(), any(), any(), isNull(), eq("女兒"),
                eq(ScheduleItem.Recurrence.WEEKLY), eq(java.time.LocalDate.of(2026, 9, 30)));
        verify(scheduleService, times(2)).createSchedule(
                anyString(), any(), any(), isNull(), eq(ScheduleItem.Recurrence.WEEKLY),
                eq(java.time.LocalDate.of(2026, 9, 30)), eq(ScheduleItem.Category.FAMILY));
    }

    @Test
    void newTranscriptContinuesFromExistingScheduleAndNeverRepeatsKnownAnswers() {
        stubExistingSchedule();
        AtomicInteger mutations = new AtomicInteger();

        IntentResult target = answer("怎麼合併之後就沒有問？是要怎麼接送了呢？", mutations)
                .orElseThrow();
        assertThat(target.message())
                .contains("我指的是行程「送女兒到夏恩英語上課」", "誰負責送去", "誰負責接回")
                .doesNotContain("lastScheduleId", "使用者詢問", "系統沒有");

        IntentResult clarified = answer("你是指哪一個行程？", mutations).orElseThrow();
        assertThat(clarified.message()).contains("送女兒到夏恩英語上課");

        IntentResult filled = answer(
                "送女兒到夏恩英語上課是我送，也是我接，接回的時間就是英文課的下課時間12點，"
                        + "要接的地點也是在夏恩英語",
                mutations).orElseThrow();
        assertThat(filled.message())
                .contains("送去：我", "接回：我", "12:00從夏恩英語接")
                .contains("送去從哪裡出發", "送去預計幾點出發", "接回行程預計幾點結束")
                .doesNotContain("誰負責送去", "誰負責接回", "從哪裡接", "AI 暫時無法");

        IntentResult repeated = answer(
                "送女兒到夏恩英語上課是我送也是我接，12點在夏恩英語接", mutations)
                .orElseThrow();
        assertThat(repeated.message())
                .doesNotContain("誰負責送去", "誰負責接回", "從哪裡接", "AI 暫時無法");
        assertThat(mutations).hasValue(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "這個英文課我負責來回接送，12點在夏恩英語接",
        "我會送她去，也會去接她，下課十二點夏恩英語接",
        "送跟接都是我，接人的地方是夏恩英語，時間12點",
        "這個行程我送也我接，十二點到夏恩英語接"
    })
    void commonTransportPhrasingsFillPeopleAndPickupPlace(String text) {
        stubExistingSchedule();

        IntentResult result = answer(text, new AtomicInteger()).orElseThrow();

        assertThat(result.message())
                .contains("送去：我", "接回：我", "夏恩英語接")
                .doesNotContain("誰負責送去", "誰負責接回", "從哪裡接");
    }

    private void stubExistingSchedule() {
        ScheduleItem source = org.mockito.Mockito.mock(ScheduleItem.class);
        when(source.getId()).thenReturn(14L);
        when(source.getTitle()).thenReturn("送女兒到夏恩英語上課");
        when(source.getStatus()).thenReturn(
                com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.CONFIRMED);
        when(source.getStartAt()).thenReturn(Instant.parse("2026-07-25T02:00:00Z"));
        when(source.getEndAt()).thenReturn(Instant.parse("2026-07-25T04:00:00Z"));
        when(source.getRecurrenceUntil()).thenReturn(java.time.LocalDate.of(2026, 9, 26));
        when(conversationContext.snapshot()).thenReturn(new ConversationSnapshot(
                null, 14L, null, java.util.List.of(), java.util.List.of(14L),
                null, null, null));
        when(scheduleService.getSchedule(14L)).thenReturn(source);
    }

    private Optional<IntentResult> answer(String text, AtomicInteger mutations) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(SCOPE)) {
            return service.answer(text, mutations::incrementAndGet);
        }
    }

    private void assertCompleted(IntentResult result) {
        assertThat(result.action()).isEqualTo(IntentResult.Action.BATCH_EXECUTED);
        assertThat(result.message())
                .contains("已建立固定上課與接送流程", "女兒上夏恩英語", "送女兒到夏恩英語",
                        "從夏恩英語", "接女兒", "2026-09-30", "課程時段不會算成你整段忙碌")
                .doesNotContain("待補");
        verify(scheduleService, times(1)).createFamilySchedule(
                anyString(), any(), any(), isNull(), eq("女兒"),
                eq(ScheduleItem.Recurrence.WEEKLY), eq(java.time.LocalDate.of(2026, 9, 30)));
        verify(scheduleService, times(2)).createSchedule(
                anyString(), any(), any(), isNull(), eq(ScheduleItem.Recurrence.WEEKLY),
                eq(java.time.LocalDate.of(2026, 9, 30)), eq(ScheduleItem.Category.FAMILY));
    }

    private ScheduleService.ScheduleDecision decision(
            String title, Instant start, Instant end) {
        ScheduleItem item = ScheduleItem.propose(title, start, end, null, CLOCK.instant());
        item.repeat(ScheduleItem.Recurrence.WEEKLY, java.time.LocalDate.of(2026, 9, 30), CLOCK.instant());
        // The mock keeps created schedules so a second utterance exercises semantic idempotency.
        // Production persistence supplies the same list through ScheduleService.
        schedules.add(item);
        return new ScheduleService.ScheduleDecision(item, null);
    }
}

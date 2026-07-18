package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleCorrectionConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final Instant ORIGINAL_START = Instant.parse("2026-07-19T06:00:00Z");
    private static final Instant ORIGINAL_END = Instant.parse("2026-07-19T07:00:00Z");

    @Mock
    private ScheduleService scheduleService;
    @Mock
    private ScheduleItem meeting;
    @Mock
    private FeasibilityResult feasibility;

    @Test
    void lastCorrectionWinsAndKeepsDurationAndPlaceWithoutCreating() {
        AtomicInteger mutations = new AtomicInteger();
        when(meeting.getId()).thenReturn(7L);
        when(meeting.getTitle()).thenReturn("產品會議");
        when(meeting.getStartAt()).thenReturn(ORIGINAL_START);
        when(meeting.getEndAt()).thenReturn(ORIGINAL_END);
        when(meeting.getRecurrence()).thenReturn(ScheduleItem.Recurrence.NONE);
        when(scheduleService.findReschedulableSchedulesStartingBetween(
                ORIGINAL_START, ORIGINAL_START.plusSeconds(60))).thenReturn(List.of(meeting));
        when(feasibility.feasible()).thenReturn(true);
        when(scheduleService.reschedule(7L,
                Instant.parse("2026-07-19T07:30:00Z"),
                Instant.parse("2026-07-19T08:30:00Z")))
                .thenReturn(new ScheduleDecision(meeting, feasibility));

        IntentResult result = service().answer(
                "明天下午兩點的會改三點，喔不是，是改到三點半，地點一樣線上，原本一小時不要變",
                mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_RESCHEDULED);
        assertThat(result.message()).contains(
                "07/19 15:30", "16:30", "前面說的時間未採用", "60 分鐘",
                "地點設定保持不變", "沒有新增重複行程");
        verify(scheduleService).reschedule(7L,
                Instant.parse("2026-07-19T07:30:00Z"),
                Instant.parse("2026-07-19T08:30:00Z"));
    }

    @Test
    void multipleOriginalCandidatesAreListedAndNothingIsChanged() {
        ScheduleItem another = org.mockito.Mockito.mock(ScheduleItem.class);
        when(meeting.getId()).thenReturn(7L);
        when(meeting.getTitle()).thenReturn("產品會議");
        when(another.getId()).thenReturn(8L);
        when(another.getTitle()).thenReturn("部門會議");
        when(scheduleService.findReschedulableSchedulesStartingBetween(
                ORIGINAL_START, ORIGINAL_START.plusSeconds(60)))
                .thenReturn(List.of(meeting, another));

        IntentResult result = service().answer(
                "明天下午兩點的會改三點，不是，改到三點半", () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("#7 產品會議", "#8 部門會議", "都沒有修改");
        verify(scheduleService, never()).reschedule(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recurringCandidateRequiresScopeInsteadOfEditingSeries() {
        when(meeting.getTitle()).thenReturn("週會");
        when(meeting.getRecurrence()).thenReturn(ScheduleItem.Recurrence.WEEKLY);
        when(scheduleService.findReschedulableSchedulesStartingBetween(
                ORIGINAL_START, ORIGINAL_START.plusSeconds(60))).thenReturn(List.of(meeting));

        IntentResult result = service().answer(
                "明天下午兩點的會改三點，不是，改到三點半", () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("只改明天這一次", "整個系列", "目前沒有修改");
        verify(scheduleService, never()).reschedule(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void singleChangeOrPlaceMutationStaysOnGeneralIntentPath() {
        assertThat(service().answer("明天下午兩點的會改到三點", () -> { })).isEmpty();
        assertThat(service().answer(
                "明天下午兩點的會改三點，不是改到三點半，地點改成公司", () -> { })).isEmpty();
    }

    private ScheduleCorrectionConversationService service() {
        return new ScheduleCorrectionConversationService(scheduleService, CLOCK);
    }
}

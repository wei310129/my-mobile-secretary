package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyScheduleOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");

    @Mock
    private ScheduleService scheduleService;
    @Mock
    private ConversationContextService contextService;

    private DailyScheduleOverviewService service;

    @BeforeEach
    void setUp() {
        service = new DailyScheduleOverviewService(scheduleService, contextService);
    }

    @Test
    void combinesWorkdayRoutineAndOneTimeSchedulesWithStatuses() {
        ScheduleItem workday = proposed("上班日通勤與上班（起床到下班）",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        ScheduleItem meeting = proposed("專案週會",
                "2026-07-17T10:00:00+08:00", "2026-07-17T11:00:00+08:00");
        meeting.confirm(NOW);
        ScheduleItem rehearsal = proposed("簡報排練",
                "2026-07-17T11:00:00+08:00", "2026-07-17T12:00:00+08:00");
        rehearsal.confirm(NOW);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workday, meeting, rehearsal));

        IntentResult result = service.overview(LocalDate.of(2026, 7, 17));

        assertThat(result.message())
                .contains("📅 2026/07/17", "📌 07:00–19:15", "上班日通勤與上班（起床到下班）｜待確認")
                .contains("💼 10:00–11:00", "專案週會｜已確認")
                .contains("💻 11:00–12:00", "簡報排練｜已確認")
                .contains("位於", "不需要改期", "請確認是否把上述當日項目併入固定行程");
        assertThat(result.message()).doesNotContain("固定行程:", "當日行程:");
        assertThat(result.message().indexOf("📌 07:00–19:15"))
                .isLessThan(result.message().indexOf("📝 已位於固定行程內的當日項目"));
        verify(contextService).rememberSchedule(workday);
    }

    @Test
    void workdayRoutineDoesNotAppearOnWeekend() {
        ScheduleItem workday = proposed("上班日通勤與上班",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workday));

        IntentResult result = service.overview(LocalDate.of(2026, 7, 18));

        assertThat(result.message()).contains("目前沒有固定或當日行程");
    }

    @Test
    void workdayRoutineDoesNotAppearBeforeItsAnchorOrAfterItsCutoff() {
        ScheduleItem workday = proposed("上班日通勤與上班",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, LocalDate.of(2026, 7, 31), NOW);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workday));

        assertThat(service.overview(LocalDate.of(2026, 7, 16)).message())
                .contains("目前沒有固定或當日行程");
        assertThat(service.overview(LocalDate.of(2026, 8, 3)).message())
                .contains("目前沒有固定或當日行程");
    }

    @Test
    void oneTimeScheduleUsesContentSpecificEmoji() {
        ScheduleItem exercise = proposed("晚上運動",
                "2026-07-17T20:00:00+08:00", "2026-07-17T21:00:00+08:00");
        exercise.confirm(NOW);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(exercise));

        assertThat(service.overview(LocalDate.of(2026, 7, 17)).message())
                .contains("🏃 20:00–21:00", "晚上運動｜已確認");
    }

    @Test
    void historicalDayShowsOnlySchedulesOnThatDate() {
        ScheduleItem yesterday = proposed("昨天運動",
                "2026-07-15T20:00:00+08:00", "2026-07-15T21:00:00+08:00");
        yesterday.confirm(NOW);
        ScheduleItem tomorrow = proposed("明天會議",
                "2026-07-17T10:00:00+08:00", "2026-07-17T11:00:00+08:00");
        tomorrow.confirm(NOW);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(yesterday, tomorrow));

        IntentResult result = service.overview(LocalDate.of(2026, 7, 15));

        assertThat(result.message()).contains("2026/07/15", "昨天運動")
                .doesNotContain("明天會議");
    }

    @Test
    void mergeConfirmationConfirmsTheProposedRecurringSchedule() {
        ScheduleItem workday = proposed("上班日通勤與上班",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        when(contextService.scheduleIdAt(null)).thenReturn(10L);
        when(scheduleService.getSchedule(10L)).thenReturn(workday);

        IntentResult result = service.confirmMerge();

        verify(scheduleService).confirmSchedule(10L);
        assertThat(result.message()).contains("已確認固定行程", "不會要求改期");
    }

    @Test
    void nestedScheduleDecisionAsksAboutMergeInsteadOfNewTime() {
        ScheduleItem workday = proposed("上班日通勤與上班",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        FeasibilityIssue issue = new FeasibilityIssue(
                FeasibilityIssue.Type.NESTED_IN_RECURRING_SCHEDULE,
                "專案週會位於固定行程內",
                2L);

        IntentResult result = IntentResult.scheduleDecided(new ScheduleDecision(
                workday, FeasibilityResult.withIssues(List.of(issue))));

        assertThat(result.message()).contains("是否併入固定行程", "不會自行確認或要求改期")
                .doesNotContain("指定新時間");
    }

    @Test
    void mergeRejectionByTitleKeepsScheduleAndAsksUserToDecide() {
        ScheduleItem workday = proposed("上班日通勤與上班",
                "2026-07-17T07:00:00+08:00", "2026-07-17T19:15:00+08:00");
        workday.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        ScheduleItem rehearsal = proposed("簡報排練",
                "2026-07-17T14:00:00+08:00", "2026-07-17T15:00:00+08:00");
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workday, rehearsal));

        IntentResult result = service.rejectMerge("簡報排練不要併到上班固定行程");

        assertThat(result.message())
                .contains("「簡報排練」不會併入固定行程「上班日通勤與上班」")
                .contains("14:00–15:00", "維持原時間", "不會由我代你決定");
        // 拒絕併入不代表取消或確認:任何狀態變更都必須等使用者決定
        verify(scheduleService, org.mockito.Mockito.never()).confirmSchedule(org.mockito.ArgumentMatchers.any());
        verify(scheduleService, org.mockito.Mockito.never()).cancelSchedule(org.mockito.ArgumentMatchers.any());
        verify(contextService).rememberSchedule(rehearsal);
    }

    @Test
    void mergeRejectionWithoutTitleFallsBackToContextSchedule() {
        ScheduleItem rehearsal = proposed("簡報排練",
                "2026-07-17T14:00:00+08:00", "2026-07-17T15:00:00+08:00");
        when(scheduleService.listSchedules(null)).thenReturn(List.of(rehearsal));
        when(contextService.scheduleIdAt(null)).thenReturn(7L);
        when(scheduleService.getSchedule(7L)).thenReturn(rehearsal);

        IntentResult result = service.rejectMerge("不要併入，你有聽懂嗎？");

        assertThat(result.message()).contains("「簡報排練」不會併入固定行程", "14:00–15:00");
    }

    @Test
    void mergeRejectionWithoutAnyTargetAsksWhichSchedule() {
        when(scheduleService.listSchedules(null)).thenReturn(List.of());
        when(contextService.scheduleIdAt(null)).thenReturn(null);

        IntentResult result = service.rejectMerge("不要併入");

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("哪個行程");
    }

    private ScheduleItem proposed(String title, String start, String end) {
        return ScheduleItem.propose(title, Instant.parse(start), Instant.parse(end), null, NOW);
    }
}

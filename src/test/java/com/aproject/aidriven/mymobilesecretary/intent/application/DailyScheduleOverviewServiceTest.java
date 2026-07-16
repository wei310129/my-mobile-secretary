package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
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
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workday, meeting));

        IntentResult result = service.overview(LocalDate.of(2026, 7, 17));

        assertThat(result.message())
                .contains("2026/07/17", "固定行程", "07:00–19:15｜上班日通勤與上班（起床到下班）｜待確認")
                .contains("當日行程", "10:00–11:00｜專案週會｜已確認")
                .contains("位於", "不需要改期", "請確認是否把上述當日項目併入固定行程");
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

    private ScheduleItem proposed(String title, String start, String end) {
        return ScheduleItem.propose(title, Instant.parse(start), Instant.parse(end), null, NOW);
    }
}

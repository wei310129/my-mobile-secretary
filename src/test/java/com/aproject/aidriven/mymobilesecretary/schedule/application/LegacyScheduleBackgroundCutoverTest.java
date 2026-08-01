package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import org.junit.jupiter.api.Test;

class LegacyScheduleBackgroundCutoverTest {

    @Test
    void globalCalendarV2CutoverStopsEveryLegacyScheduleBackgroundEntry() {
        CalendarV2RoutingService routing = mock(CalendarV2RoutingService.class);
        WorkspaceBackgroundRunner runner = mock(WorkspaceBackgroundRunner.class);
        ScheduleService schedules = mock(ScheduleService.class);
        PendingPromptService pendingPrompts = mock(PendingPromptService.class);
        ScheduleFollowUpService followUps = mock(ScheduleFollowUpService.class);
        when(routing.globalCutoverEnabled()).thenReturn(true);

        new RecurringScheduleWorker(schedules, runner, routing).poll();
        new PendingPromptWorker(pendingPrompts, runner, routing).poll();
        new ScheduleFollowUpWorker(followUps, runner, routing).poll();

        verify(runner, never()).forEachWorkspace(any(), any());
        verify(schedules, never()).rolloverDueRecurringSchedules();
        verify(pendingPrompts, never()).promptIfIdle();
        verify(followUps, never()).planFollowUpsForEndedSchedules();
        verify(followUps, never()).askDueFollowUps();
    }

    @Test
    void flagOffPreservesEveryLegacyScheduleBackgroundEntry() {
        CalendarV2RoutingService routing = mock(CalendarV2RoutingService.class);
        WorkspaceBackgroundRunner runner = mock(WorkspaceBackgroundRunner.class);
        when(routing.globalCutoverEnabled()).thenReturn(false);

        new RecurringScheduleWorker(mock(ScheduleService.class), runner, routing).poll();
        new PendingPromptWorker(mock(PendingPromptService.class), runner, routing).poll();
        new ScheduleFollowUpWorker(mock(ScheduleFollowUpService.class), runner, routing).poll();

        verify(runner).forEachWorkspace(eq("recurring-schedule"), any());
        verify(runner).forEachWorkspace(eq("pending-prompt"), any());
        verify(runner).forEachWorkspace(eq("schedule-follow-up"), any());
    }
}

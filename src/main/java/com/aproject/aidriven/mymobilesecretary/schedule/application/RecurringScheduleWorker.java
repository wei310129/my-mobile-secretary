package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 固定行程 rollover 的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class RecurringScheduleWorker {

    private final ScheduleService scheduleService;
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarV2RoutingService calendarRouting;

    public RecurringScheduleWorker(ScheduleService scheduleService,
                                   WorkspaceBackgroundRunner workspaceRunner,
                                   CalendarV2RoutingService calendarRouting) {
        this.scheduleService = scheduleService;
        this.workspaceRunner = workspaceRunner;
        this.calendarRouting = calendarRouting;
    }

    @Scheduled(fixedDelayString = "${app.schedule.recurring-poll-interval:10m}")
    public void poll() {
        if (calendarRouting.globalCutoverEnabled()) {
            return;
        }
        workspaceRunner.forEachWorkspace("recurring-schedule",
                ignored -> scheduleService.rolloverDueRecurringSchedules());
    }
}

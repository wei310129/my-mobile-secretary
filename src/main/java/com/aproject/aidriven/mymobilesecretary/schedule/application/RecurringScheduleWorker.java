package com.aproject.aidriven.mymobilesecretary.schedule.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 固定行程 rollover 的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class RecurringScheduleWorker {

    private final ScheduleService scheduleService;

    public RecurringScheduleWorker(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(fixedDelayString = "${app.schedule.recurring-poll-interval:10m}")
    public void poll() {
        scheduleService.rolloverDueRecurringSchedules();
    }
}

package com.aproject.aidriven.mymobilesecretary.schedule.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行程結果追蹤的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 * 排定與發問拆兩步但同一輪跑:先補排新結束的行程,再發到期的詢問。
 */
@Component
public class ScheduleFollowUpWorker {

    private final ScheduleFollowUpService followUpService;

    public ScheduleFollowUpWorker(ScheduleFollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    @Scheduled(fixedDelayString = "${app.follow-up.poll-interval:1m}")
    public void poll() {
        followUpService.planFollowUpsForEndedSchedules();
        followUpService.askDueFollowUps();
    }
}

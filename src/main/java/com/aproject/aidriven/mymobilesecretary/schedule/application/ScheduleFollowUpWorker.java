package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行程結果追蹤的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 * 排定與發問拆兩步但同一輪跑:先補排新結束的行程,再發到期的詢問。
 */
@Component
public class ScheduleFollowUpWorker {

    private final ScheduleFollowUpService followUpService;
    private final WorkspaceBackgroundRunner workspaceRunner;

    public ScheduleFollowUpWorker(ScheduleFollowUpService followUpService,
                                  WorkspaceBackgroundRunner workspaceRunner) {
        this.followUpService = followUpService;
        this.workspaceRunner = workspaceRunner;
    }

    @Scheduled(fixedDelayString = "${app.follow-up.poll-interval:1m}")
    public void poll() {
        workspaceRunner.forEachWorkspace("schedule-follow-up", ignored -> {
            followUpService.planFollowUpsForEndedSchedules();
            followUpService.askDueFollowUps();
        });
    }
}

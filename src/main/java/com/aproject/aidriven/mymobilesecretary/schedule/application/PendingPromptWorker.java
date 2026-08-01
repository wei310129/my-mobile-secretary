package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pending 空閒詢問的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class PendingPromptWorker {

    private final PendingPromptService promptService;
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final CalendarV2RoutingService calendarRouting;

    public PendingPromptWorker(PendingPromptService promptService,
                               WorkspaceBackgroundRunner workspaceRunner,
                               CalendarV2RoutingService calendarRouting) {
        this.promptService = promptService;
        this.workspaceRunner = workspaceRunner;
        this.calendarRouting = calendarRouting;
    }

    @Scheduled(fixedDelayString = "${app.pending.prompt.poll-interval:10m}")
    public void poll() {
        if (calendarRouting.globalCutoverEnabled()) {
            return;
        }
        workspaceRunner.forEachWorkspace("pending-prompt",
                ignored -> promptService.promptIfIdle());
    }
}

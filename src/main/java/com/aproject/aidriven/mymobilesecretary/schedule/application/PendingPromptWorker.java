package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pending 空閒詢問的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class PendingPromptWorker {

    private final PendingPromptService promptService;
    private final WorkspaceBackgroundRunner workspaceRunner;

    public PendingPromptWorker(PendingPromptService promptService,
                               WorkspaceBackgroundRunner workspaceRunner) {
        this.promptService = promptService;
        this.workspaceRunner = workspaceRunner;
    }

    @Scheduled(fixedDelayString = "${app.pending.prompt.poll-interval:10m}")
    public void poll() {
        workspaceRunner.forEachWorkspace("pending-prompt",
                ignored -> promptService.promptIfIdle());
    }
}

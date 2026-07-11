package com.aproject.aidriven.mymobilesecretary.schedule.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pending 空閒詢問的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class PendingPromptWorker {

    private final PendingPromptService promptService;

    public PendingPromptWorker(PendingPromptService promptService) {
        this.promptService = promptService;
    }

    @Scheduled(fixedDelayString = "${app.pending.prompt.poll-interval:10m}")
    public void poll() {
        promptService.promptIfIdle();
    }
}

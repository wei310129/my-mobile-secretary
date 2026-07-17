package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 天氣主動提醒的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class WeatherAlertWorker {

    private final WeatherAlertService alertService;
    private final WorkspaceBackgroundRunner workspaceRunner;

    public WeatherAlertWorker(WeatherAlertService alertService,
                              WorkspaceBackgroundRunner workspaceRunner) {
        this.alertService = alertService;
        this.workspaceRunner = workspaceRunner;
    }

    @Scheduled(fixedDelayString = "${app.weather.alert.poll-interval:30m}")
    public void poll() {
        workspaceRunner.forEachWorkspace("weather-alert", ignored -> {
            alertService.remindUmbrellaIfRainy();
            alertService.askScheduleAdjustmentIfHeavyRain();
        });
    }
}

package com.aproject.aidriven.mymobilesecretary.planner.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 天氣主動提醒的背景輪詢(排程開關同延遲提醒:app.scheduling.enabled)。
 */
@Component
public class WeatherAlertWorker {

    private final WeatherAlertService alertService;

    public WeatherAlertWorker(WeatherAlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${app.weather.alert.poll-interval:30m}")
    public void poll() {
        alertService.remindUmbrellaIfRainy();
        alertService.askScheduleAdjustmentIfHeavyRain();
    }
}

package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 天氣主動提醒設定(使用者 2026-07-15 要求的主動性:不是被問才回)。
 *
 * @param start              提醒時窗開始(台北時間;帶傘提醒的「出門前」近似)
 * @param end                提醒時窗結束(晚上不吵人)
 * @param heavyRainThreshold 降雨機率高於此值視為大雨風險,主動問要不要調整行程
 * @param scheduleLookahead  大雨警示只看這段時間內開始的已確認行程
 */
@ConfigurationProperties(prefix = "app.weather.alert")
public record WeatherAlertProperties(
        @DefaultValue("07:00") LocalTime start,
        @DefaultValue("21:00") LocalTime end,
        @DefaultValue("80") int heavyRainThreshold,
        @DefaultValue("6h") Duration scheduleLookahead
) {
}

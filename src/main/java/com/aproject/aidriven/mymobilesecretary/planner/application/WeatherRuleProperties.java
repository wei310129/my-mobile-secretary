package com.aproject.aidriven.mymobilesecretary.planner.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 天氣規則設定。
 *
 * @param enabled                  關閉時提醒不附天氣建議(測試環境必關,避免打真實 API)
 * @param county                   查詢預報的縣市(氣象署用「臺」;使用者活動範圍先固定一個縣市,
 *                                 之後可依地點座標對應縣市)
 * @param rainProbabilityThreshold 降雨機率達此值(%)就提醒帶傘/少買
 * @param highTempThreshold        最高溫達此值(°C)就提醒生鮮早買
 */
@ConfigurationProperties(prefix = "app.weather")
public record WeatherRuleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("新北市") String county,
        @DefaultValue("60") int rainProbabilityThreshold,
        @DefaultValue("34") int highTempThreshold
) {
}

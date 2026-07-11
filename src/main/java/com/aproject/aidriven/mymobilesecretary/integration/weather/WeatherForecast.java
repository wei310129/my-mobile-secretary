package com.aproject.aidriven.mymobilesecretary.integration.weather;

/**
 * 我方的天氣預報模型(不把氣象署原始 response 洩漏到其他模組)。
 *
 * @param county                 縣市名,例如「新北市」
 * @param description            天氣現象,例如「多雲時晴」
 * @param rainProbabilityPercent 降雨機率(%)
 * @param minTemp                最低溫(°C)
 * @param maxTemp                最高溫(°C)
 */
public record WeatherForecast(
        String county,
        String description,
        int rainProbabilityPercent,
        int minTemp,
        int maxTemp
) {
}

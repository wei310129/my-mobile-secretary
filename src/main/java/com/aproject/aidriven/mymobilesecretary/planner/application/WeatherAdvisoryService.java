package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaWeatherClient;
import com.aproject.aidriven.mymobilesecretary.integration.weather.WeatherForecast;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 天氣風險規則(確定性,LLM 不參與):把預報轉成一句實用建議,附在提醒訊息後。
 *
 * 關鍵規則:天氣查詢失敗回 empty——提醒照送,絕不因為附加資訊拖垮核心
 * (可靠度 > 聰明度)。
 */
@Service
public class WeatherAdvisoryService {

    private static final Logger log = LoggerFactory.getLogger(WeatherAdvisoryService.class);

    private final CwaWeatherClient weatherClient;
    private final WeatherRuleProperties properties;

    public WeatherAdvisoryService(CwaWeatherClient weatherClient, WeatherRuleProperties properties) {
        this.weatherClient = weatherClient;
        this.properties = properties;
    }

    /**
     * 取得目前天氣建議;沒有需要提醒的天氣風險、或查詢失敗時回 empty。
     */
    public Optional<String> currentAdvisory() {
        Optional<WeatherForecast> current = currentForecast();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        WeatherForecast forecast = current.get();

        List<String> tips = new ArrayList<>();
        if (forecast.rainProbabilityPercent() >= properties.rainProbabilityThreshold()) {
            tips.add("降雨機率 %d%%,記得帶傘、東西別買太多".formatted(forecast.rainProbabilityPercent()));
        }
        if (forecast.maxTemp() >= properties.highTempThreshold()) {
            tips.add("高溫 %d 度,生鮮早點買、盡快回家冰".formatted(forecast.maxTemp()));
        }
        return tips.isEmpty() ? Optional.empty() : Optional.of(String.join(";", tips));
    }

    /** 給對話查詢與條件提醒共用的安全預報入口。 */
    public Optional<WeatherForecast> currentForecast() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(weatherClient.getForecast(properties.county()));
        } catch (Exception e) {
            log.warn("Weather forecast unavailable", e);
            return Optional.empty();
        }
    }

    public Optional<String> describeCurrentForecast() {
        return currentForecast().map(f -> "%s目前預報:%s,降雨機率 %d%%,氣溫 %d–%d°C。"
                .formatted(f.county(), f.description(), f.rainProbabilityPercent(),
                        f.minTemp(), f.maxTemp()));
    }

    public Optional<Boolean> isRainy() {
        return currentForecast().map(f ->
                f.rainProbabilityPercent() >= properties.rainProbabilityThreshold());
    }
}

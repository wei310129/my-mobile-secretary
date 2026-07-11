package com.aproject.aidriven.mymobilesecretary.api.weather;

import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaWeatherClient;
import com.aproject.aidriven.mymobilesecretary.integration.weather.WeatherForecast;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 天氣查詢 API:給手動查詢/除錯;planner 的天氣規則(Phase 2D)直接用 client。
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final CwaWeatherClient weatherClient;

    public WeatherController(CwaWeatherClient weatherClient) {
        this.weatherClient = weatherClient;
    }

    /** 查縣市 36 小時預報(注意氣象署用「臺」:臺北市/臺中市)。 */
    @GetMapping
    public WeatherForecast getForecast(@RequestParam(defaultValue = "新北市") String county) {
        return weatherClient.getForecast(county);
    }
}

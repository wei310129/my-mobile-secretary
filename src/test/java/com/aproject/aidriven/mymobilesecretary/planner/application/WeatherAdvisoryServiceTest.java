package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaWeatherClient;
import com.aproject.aidriven.mymobilesecretary.integration.weather.WeatherForecast;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 天氣規則測試:降雨/高溫建議、無風險不出聲、查詢失敗不影響。
 */
@ExtendWith(MockitoExtension.class)
class WeatherAdvisoryServiceTest {

    @Mock
    private CwaWeatherClient weatherClient;

    private WeatherAdvisoryService service(boolean enabled) {
        return new WeatherAdvisoryService(weatherClient,
                new WeatherRuleProperties(enabled, "新北市", 60, 34));
    }

    @Test
    void rainAdvisoryWhenProbabilityAtThreshold() {
        when(weatherClient.getForecast("新北市"))
                .thenReturn(new WeatherForecast("新北市", "陣雨", 60, 25, 30));

        Optional<String> advisory = service(true).currentAdvisory();

        assertThat(advisory).isPresent();
        assertThat(advisory.get()).contains("降雨機率 60%");
    }

    @Test
    void highTempAndRainCombineIntoOneAdvisory() {
        when(weatherClient.getForecast("新北市"))
                .thenReturn(new WeatherForecast("新北市", "午後雷陣雨", 70, 28, 36));

        Optional<String> advisory = service(true).currentAdvisory();

        assertThat(advisory).isPresent();
        assertThat(advisory.get()).contains("降雨機率 70%").contains("高溫 36 度");
    }

    @Test
    void calmWeatherGivesNoAdvisory() {
        when(weatherClient.getForecast("新北市"))
                .thenReturn(new WeatherForecast("新北市", "晴", 10, 20, 28));

        assertThat(service(true).currentAdvisory()).isEmpty();
    }

    /** 關鍵:天氣查詢失敗回 empty,不往外丟——提醒照送。 */
    @Test
    void forecastFailureReturnsEmptyNotException() {
        when(weatherClient.getForecast("新北市"))
                .thenThrow(new IntegrationException("CWA down"));

        assertThat(service(true).currentAdvisory()).isEmpty();
    }

    @Test
    void disabledSkipsClientEntirely() {
        assertThat(service(false).currentAdvisory()).isEmpty();
        verify(weatherClient, never()).getForecast(anyString());
    }
}

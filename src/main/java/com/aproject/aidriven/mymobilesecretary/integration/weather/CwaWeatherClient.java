package com.aproject.aidriven.mymobilesecretary.integration.weather;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 中央氣象署 36 小時天氣預報 client(資料集 F-C0032-001)。
 *
 * 快取 20 分鐘(CacheConfig):位置事件一天可能數十次觸發評估,
 * 不能每次都打外部 API。
 */
@Component
public class CwaWeatherClient {

    private final RestClient restClient;
    private final CwaProperties properties;

    public CwaWeatherClient(RestClient.Builder builder, CwaProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 取指定縣市未來 36 小時的第一時段預報。
     *
     * @param county 縣市全名,例如「新北市」「臺北市」(氣象署用「臺」不是「台」)
     * @throws IntegrationException timeout、非 2xx、格式錯誤、查無該縣市
     */
    @Cacheable(cacheNames = "weather", key = "#county")
    public WeatherForecast getForecast(String county) {
        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/rest/datastore/F-C0032-001")
                            .queryParam("Authorization", properties.apiKey())
                            .queryParam("locationName", county)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IntegrationException("CWA forecast request failed for %s".formatted(county), e);
        }
        if (root == null) {
            throw new IntegrationException("CWA returned empty body for county: " + county);
        }

        try {
            JsonNode location = root.path("records").path("location").get(0);
            if (location == null) {
                throw new IntegrationException("CWA returned no data for county: " + county);
            }
            // weatherElement: Wx(天氣現象)/PoP(降雨機率)/MinT/MaxT,各取第一個時段
            String wx = elementValue(location, "Wx");
            int pop = Integer.parseInt(elementValue(location, "PoP"));
            int minT = Integer.parseInt(elementValue(location, "MinT"));
            int maxT = Integer.parseInt(elementValue(location, "MaxT"));
            return new WeatherForecast(county, wx, pop, minT, maxT);
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException("CWA response has unexpected format", e);
        }
    }

    private String elementValue(JsonNode location, String elementName) {
        for (JsonNode element : location.path("weatherElement")) {
            if (elementName.equals(element.path("elementName").asText())) {
                return element.path("time").get(0).path("parameter").path("parameterName").asText();
            }
        }
        throw new IntegrationException("CWA element missing: " + elementName);
    }
}

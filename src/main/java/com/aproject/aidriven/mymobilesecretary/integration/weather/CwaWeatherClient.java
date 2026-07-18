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

    /**
     * 取行政區層級的一週預報第一時段。氣象署自 2024-12 起要求查詢參數使用
     * {@code LocationName}/{@code ElementName} 大寫格式；資料集使用全臺鄉鎮 F-D0047-091。
     */
    @Cacheable(cacheNames = "weather", key = "#county + ':' + #district")
    public WeatherForecast getDistrictForecast(String county, String district) {
        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/rest/datastore/F-D0047-091")
                            .queryParam("Authorization", properties.apiKey())
                            .queryParam("LocationName", district)
                            .queryParam("ElementName", "天氣預報綜合描述,12小時降雨機率,最低溫度,最高溫度")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception exception) {
            throw new IntegrationException(
                    "CWA district forecast request failed for %s%s".formatted(county, district),
                    exception);
        }
        if (root == null) {
            throw new IntegrationException("CWA returned empty district forecast");
        }
        try {
            JsonNode locations = child(child(root, "records", "Records"),
                    "Locations", "locations");
            JsonNode target = null;
            for (JsonNode group : locations) {
                String groupName = text(group, "LocationsName", "locationsName");
                if (!county.equals(groupName)) continue;
                for (JsonNode location : child(group, "Location", "location")) {
                    if (district.equals(text(location, "LocationName", "locationName"))) {
                        target = location;
                        break;
                    }
                }
            }
            if (target == null) {
                throw new IntegrationException(
                        "CWA returned no district data for %s%s".formatted(county, district));
            }
            String description = districtElementValue(target, "天氣預報綜合描述",
                    "WeatherDescription");
            int rain = Integer.parseInt(districtElementValue(target, "12小時降雨機率",
                    "ProbabilityOfPrecipitation"));
            int min = Integer.parseInt(districtElementValue(target, "最低溫度",
                    "MinTemperature"));
            int max = Integer.parseInt(districtElementValue(target, "最高溫度",
                    "MaxTemperature"));
            return new WeatherForecast(county + district, description, rain, min, max);
        } catch (IntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntegrationException("CWA district response has unexpected format", exception);
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

    private static String districtElementValue(JsonNode location, String elementName,
                                               String valueName) {
        for (JsonNode element : child(location, "WeatherElement", "weatherElement")) {
            if (!elementName.equals(text(element, "ElementName", "elementName"))) continue;
            JsonNode times = child(element, "Time", "time");
            if (!times.isArray() || times.isEmpty()) break;
            JsonNode values = child(times.get(0), "ElementValue", "elementValue");
            if (!values.isArray() || values.isEmpty()) break;
            String value = text(values.get(0), valueName,
                    Character.toLowerCase(valueName.charAt(0)) + valueName.substring(1));
            if (value != null && !value.isBlank()) return value;
        }
        throw new IntegrationException("CWA district element missing: " + elementName);
    }

    private static JsonNode child(JsonNode node, String primary, String fallback) {
        if (node == null) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        JsonNode value = node.get(primary);
        return value == null ? node.path(fallback) : value;
    }

    private static String text(JsonNode node, String primary, String fallback) {
        JsonNode value = node == null ? null : node.get(primary);
        if (value == null && node != null) value = node.get(fallback);
        return value == null || value.isNull() ? null : value.asText();
    }
}

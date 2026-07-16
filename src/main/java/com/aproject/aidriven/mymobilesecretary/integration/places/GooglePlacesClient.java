package com.aproject.aidriven.mymobilesecretary.integration.places;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Google Places API(New)Text Search client:地點名 → 完整資訊(地址/座標/類型)。
 *
 * 關鍵規則:FieldMask 只要用到的欄位——Places API 按欄位分級計費,
 * 多要欄位就是多付錢;response 轉成我方 PlaceCandidate,不洩漏原始格式。
 */
@Component
public class GooglePlacesClient {

    private final RestClient restClient;
    private final GooglePlacesProperties properties;

    public GooglePlacesClient(RestClient.Builder builder, GooglePlacesProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 是否已設定金鑰且啟用(呼叫端據此決定要不要查)。 */
    public boolean usable() {
        return properties.usable();
    }

    /**
     * 文字搜尋,取第一筆(繁中、台灣偏好)。
     *
     * @return empty 表示 Google 也查不到這個名字
     * @throws IntegrationException timeout、非 2xx、格式錯誤
     */
    public Optional<PlaceCandidate> searchFirst(String query) {
        JsonNode root;
        try {
            root = restClient.post()
                    .uri("/v1/places:searchText")
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask",
                            "places.displayName,places.formattedAddress,places.location,places.primaryTypeDisplayName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "textQuery", query,
                            "languageCode", "zh-TW",
                            "regionCode", "TW"))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IntegrationException("Google Places search failed for %s".formatted(query), e);
        }
        if (root == null || root.path("places").isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode place = root.path("places").get(0);
            return Optional.of(new PlaceCandidate(
                    place.path("displayName").path("text").asText(query),
                    place.path("formattedAddress").asText(null),
                    place.path("location").path("latitude").asDouble(),
                    place.path("location").path("longitude").asDouble(),
                    place.path("primaryTypeDisplayName").path("text").asText(null)));
        } catch (Exception e) {
            throw new IntegrationException("Google Places response has unexpected format", e);
        }
    }

    /**
     * 餐廳文字搜尋,取第一筆(訂位引導流程專用)。
     *
     * 與 {@link #searchFirst} 分開:這裡的 FieldMask 多要營業時間、網站、電話與友善設施欄位,
     * 屬較高計費層級,只有訂餐廳流程需要,不可讓一般地點綁定共用而墊高每次查詢成本。
     *
     * @return empty 表示 Google 也查不到
     * @throws IntegrationException timeout、非 2xx、格式錯誤
     */
    public Optional<RestaurantCandidate> searchRestaurantFirst(String query) {
        JsonNode root;
        try {
            root = restClient.post()
                    .uri("/v1/places:searchText")
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask",
                            "places.displayName,places.formattedAddress,places.location,"
                                    + "places.websiteUri,places.googleMapsUri,places.nationalPhoneNumber,"
                                    + "places.regularOpeningHours.weekdayDescriptions,places.reservable,"
                                    + "places.allowsDogs,places.goodForChildren,"
                                    + "places.accessibilityOptions.wheelchairAccessibleEntrance")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "textQuery", query,
                            "languageCode", "zh-TW",
                            "regionCode", "TW"))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IntegrationException("Google Places restaurant search failed for %s".formatted(query), e);
        }
        if (root == null || root.path("places").isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode place = root.path("places").get(0);
            java.util.List<String> openingHours = new java.util.ArrayList<>();
            place.path("regularOpeningHours").path("weekdayDescriptions")
                    .forEach(day -> openingHours.add(day.asText()));
            return Optional.of(new RestaurantCandidate(
                    place.path("displayName").path("text").asText(query),
                    place.path("formattedAddress").asText(null),
                    place.path("location").path("latitude").asDouble(),
                    place.path("location").path("longitude").asDouble(),
                    place.path("websiteUri").asText(null),
                    place.path("googleMapsUri").asText(null),
                    place.path("nationalPhoneNumber").asText(null),
                    java.util.List.copyOf(openingHours),
                    nullableBoolean(place, "reservable"),
                    nullableBoolean(place, "allowsDogs"),
                    nullableBoolean(place, "goodForChildren"),
                    place.path("accessibilityOptions").hasNonNull("wheelchairAccessibleEntrance")
                            ? place.path("accessibilityOptions").path("wheelchairAccessibleEntrance").asBoolean()
                            : null));
        } catch (Exception e) {
            throw new IntegrationException("Google Places restaurant response has unexpected format", e);
        }
    }

    /** 欄位缺席時要區分「不知道」與 false,不可用 asBoolean 的預設值。 */
    private static Boolean nullableBoolean(JsonNode place, String field) {
        return place.hasNonNull(field) ? place.path(field).asBoolean() : null;
    }

    /**
     * Google 查到的地點候選(我方模型)。
     *
     * @param type 主類型的顯示名(如「超市」),可能為 null
     */
    public record PlaceCandidate(String name, String address, double latitude, double longitude, String type) {
    }

    /**
     * 餐廳候選(訂位引導流程用);Boolean 欄位 null 代表 Google 沒提供,要與 false(明確不支援)區分。
     *
     * @param openingHours 每週各天營業時間描述(如「星期五: 11:00 – 21:00」),查不到為空清單
     */
    public record RestaurantCandidate(String name, String address, double latitude, double longitude,
                                      String websiteUri, String googleMapsUri, String phoneNumber,
                                      java.util.List<String> openingHours, Boolean reservable,
                                      Boolean allowsDogs, Boolean goodForChildren,
                                      Boolean wheelchairAccessible) {
    }
}

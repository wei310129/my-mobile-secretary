package com.aproject.aidriven.mymobilesecretary.integration.places;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Set<String> MAPS_HOSTS = Set.of(
            "maps.app.goo.gl", "goo.gl", "google.com", "www.google.com", "maps.google.com");
    private static final Pattern AT_COORDINATES = Pattern.compile(
            "/@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern DATA_COORDINATES = Pattern.compile(
            "!3d(-?\\d{1,2}(?:\\.\\d+)?)!4d(-?\\d{1,3}(?:\\.\\d+)?)");

    private final RestClient restClient;
    private final GooglePlacesProperties properties;
    private final MapsRedirectFetcher mapsRedirectFetcher;

    @Autowired
    public GooglePlacesClient(RestClient.Builder builder, GooglePlacesProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        HttpClient mapsLinkClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapsRedirectFetcher = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.timeout())
                    .GET()
                    .build();
            HttpResponse<Void> response =
                    mapsLinkClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 300 || response.statusCode() >= 400) {
                return Optional.empty();
            }
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalArgumentException("Google Maps redirect is missing"));
            return Optional.of(uri.resolve(location));
        };
    }

    GooglePlacesClient(
            RestClient.Builder builder,
            GooglePlacesProperties properties,
            MapsRedirectFetcher mapsRedirectFetcher) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        this.mapsRedirectFetcher = mapsRedirectFetcher;
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

    public static boolean isGoogleMapsLink(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            return isAllowedMapsUri(uri);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Resolves a bounded Google Maps URL without retaining or logging the supplied URL. */
    public Optional<PlaceCandidate> resolveMapsLink(String value) {
        if (!isGoogleMapsLink(value)) return Optional.empty();
        try {
            URI expanded = expandMapsLink(URI.create(value.strip()));
            Optional<Coordinates> coordinates = coordinates(expanded);
            Optional<String> resolvedName = placeName(expanded).or(() -> placeTextQuery(expanded));
            if (coordinates.isPresent()) {
                Coordinates point = coordinates.orElseThrow();
                return Optional.of(new PlaceCandidate(
                        resolvedName.orElse("Google Maps 地點"),
                        null,
                        point.latitude(),
                        point.longitude(),
                        null));
            }
            if (resolvedName.isPresent() && properties.usable()) {
                return searchFirst(resolvedName.orElseThrow());
            }
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IntegrationException("Google Maps link resolution interrupted", exception);
        } catch (Exception exception) {
            throw new IntegrationException("Google Maps link resolution failed", exception);
        }
    }

    private URI expandMapsLink(URI initial) throws Exception {
        URI current = initial;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!isAllowedMapsUri(current)) {
                throw new IllegalArgumentException("Google Maps redirect host is not allowed");
            }
            Optional<URI> target = mapsRedirectFetcher.next(current);
            if (target.isEmpty()) return current;
            URI next = target.orElseThrow();
            if (!isAllowedMapsUri(next)) {
                throw new IllegalArgumentException("Google Maps redirect host is not allowed");
            }
            if (next.equals(current)) {
                throw new IllegalArgumentException("Google Maps redirect loop detected");
            }
            current = next;
        }
        throw new IllegalArgumentException("Google Maps redirect limit exceeded");
    }

    private static boolean isAllowedMapsUri(URI uri) {
        if (uri == null || uri.getUserInfo() != null || uri.getPort() != -1) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
        return MAPS_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static Optional<Coordinates> coordinates(URI uri) {
        String value = uri.toString();
        Matcher at = AT_COORDINATES.matcher(value);
        if (at.find()) return validCoordinates(at.group(1), at.group(2));
        Matcher data = DATA_COORDINATES.matcher(value);
        if (data.find()) return validCoordinates(data.group(1), data.group(2));
        for (String pair : Optional.ofNullable(uri.getRawQuery()).orElse("").split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 1) continue;
            String key = pair.substring(0, separator);
            if (!"q".equals(key) && !"query".equals(key)) continue;
            String decoded = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",", -1);
            if (parts.length == 2) return validCoordinates(parts[0], parts[1]);
        }
        return Optional.empty();
    }

    private static Optional<Coordinates> validCoordinates(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude);
            double lon = Double.parseDouble(longitude);
            return Double.isFinite(lat) && Double.isFinite(lon)
                            && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                    ? Optional.of(new Coordinates(lat, lon))
                    : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> placeName(URI uri) {
        String path = Optional.ofNullable(uri.getRawPath()).orElse("");
        String marker = "/maps/place/";
        int start = path.indexOf(marker);
        if (start < 0) return Optional.empty();
        String remainder = path.substring(start + marker.length());
        int slash = remainder.indexOf('/');
        String encoded = slash < 0 ? remainder : remainder.substring(0, slash);
        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8).replace('+', ' ').strip();
        return decoded.isBlank() ? Optional.empty() : Optional.of(decoded);
    }

    private static Optional<String> placeTextQuery(URI uri) {
        for (String pair : Optional.ofNullable(uri.getRawQuery()).orElse("").split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 1) continue;
            String key = pair.substring(0, separator);
            if (!"q".equals(key) && !"query".equals(key)) continue;
            String decoded = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8).strip();
            if (decoded.length() < 2
                    || decoded.length() > 300
                    || decoded.indexOf('\n') >= 0
                    || decoded.indexOf('\r') >= 0) {
                return Optional.empty();
            }
            return Optional.of(decoded);
        }
        return Optional.empty();
    }

    private record Coordinates(double latitude, double longitude) {}

    @FunctionalInterface
    interface MapsRedirectFetcher {
        Optional<URI> next(URI current) throws Exception;
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

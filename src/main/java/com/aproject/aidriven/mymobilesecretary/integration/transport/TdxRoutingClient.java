package com.aproject.aidriven.mymobilesecretary.integration.transport;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * TDX 公共運輸路線規劃 client(/maas/routing)。
 *
 * 「最晚幾點出門才趕得上」的資料來源:回傳大眾運輸的實際旅行時間,
 * 取代可行性引擎的直線粗估。快取 10 分鐘(CacheConfig)。
 */
@Component
public class TdxRoutingClient {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DEPART_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient restClient;
    private final TdxTokenManager tokenManager;

    public TdxRoutingClient(RestClient.Builder builder, TdxProperties properties, TdxTokenManager tokenManager) {
        this.tokenManager = tokenManager;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 查大眾運輸旅行時間。
     *
     * @param query 起迄座標(呼叫端請先四捨五入到 4 位小數,提升快取命中率)與出發時間
     * @throws IntegrationException 失敗或查無路線(呼叫端 fallback 直線粗估)
     */
    public Duration getTransitTravelTime(TravelQuery query) {
        return getTransitRoute(query).duration();
    }

    @Cacheable(cacheNames = "travel-time", key = "#query.cacheKey()")
    public TdxRoute getTransitRoute(TravelQuery query) {
        String depart = LocalDateTime.ofInstant(query.departAt(), TAIPEI).format(DEPART_FORMAT);
        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/maas/routing")
                            .queryParam("origin", "%s,%s".formatted(query.fromLat(), query.fromLon()))
                            .queryParam("destination", "%s,%s".formatted(query.toLat(), query.toLon()))
                            // gc=1.0 → 最短時間優先;transit 涵蓋高鐵/台鐵/公車/捷運/輕軌
                            .queryParam("gc", "1.0")
                            .queryParam("top", "1")
                            .queryParam("transit", "3,4,5,6,7")
                            // 0 = walking. Never silently add a paid ride-hail first/last mile.
                            .queryParam("first_mile_mode", "0")
                            .queryParam("first_mile_time", "20")
                            .queryParam("last_mile_mode", "0")
                            .queryParam("last_mile_time", "20")
                            .queryParam("depart", depart)
                            .build())
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException("TDX routing request failed", e);
        }

        JsonNode routes = root == null ? null : root.path("data").path("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            throw new IntegrationException("TDX routing returned no routes");
        }
        JsonNode route = routes.get(0);
        long seconds = route.path("travel_time").asLong(-1);
        if (seconds < 0) {
            throw new IntegrationException("TDX routing response missing travel_time");
        }
        List<TdxTransitLeg> legs = new ArrayList<>();
        JsonNode sections = route.path("sections");
        if (sections.isArray()) {
            for (JsonNode section : sections) {
                if (!"transit".equalsIgnoreCase(section.path("type").asText())) continue;
                JsonNode transport = section.path("transport");
                String lineName = firstText(
                        transport.path("shortName"),
                        transport.path("name"),
                        transport.path("longName"));
                legs.add(new TdxTransitLeg(
                        safeText(transport.path("mode")),
                        lineName,
                        safeText(transport.path("headsign")),
                        safeText(section.path("departure").path("place").path("name")),
                        safeText(section.path("arrival").path("place").path("name"))));
            }
        }
        return new TdxRoute(Duration.ofSeconds(seconds), legs);
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            String text = safeText(value);
            if (text != null) return text;
        }
        return null;
    }

    private static String safeText(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        String text = value.asText().strip().replaceAll("[\\p{Cntrl}]", "");
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    public record TdxRoute(Duration duration, List<TdxTransitLeg> transitLegs) {
        public TdxRoute {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("TDX route duration is invalid");
            }
            transitLegs = List.copyOf(transitLegs == null ? List.of() : transitLegs);
        }
    }

    public record TdxTransitLeg(
            String mode,
            String lineName,
            String headsign,
            String departureStop,
            String arrivalStop) {}

    /**
     * 路線查詢參數。座標請先四捨五入(4 位小數 ≈ 11 公尺),
     * 出發時間截到 10 分鐘,讓相近查詢命中同一份快取。
     */
    public record TravelQuery(double fromLat, double fromLon, double toLat, double toLon, Instant departAt) {

        /** 建立已正規化(可快取)的查詢。出發時間「向上」取 10 分鐘桶:TDX 拒絕過去的出發時間。 */
        public static TravelQuery of(double fromLat, double fromLon, double toLat, double toLon, Instant departAt) {
            long ceilBucket = (departAt.getEpochSecond() + 599) / 600 * 600;
            return new TravelQuery(round(fromLat), round(fromLon), round(toLat), round(toLon),
                    Instant.ofEpochSecond(ceilBucket));
        }

        private static double round(double v) {
            return Math.round(v * 10_000) / 10_000.0;
        }

        /** Redis 快取 key。 */
        public String cacheKey() {
            return String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f@%d",
                    fromLat, fromLon, toLat, toLon, departAt.getEpochSecond());
        }
    }
}

package com.aproject.aidriven.mymobilesecretary.integration.transport;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Google Routes Compute Routes 的最小 adapter；只取 ETA、距離與 static duration。 */
@Component
public class GoogleRoutesClient {

    private static final String FIELD_MASK =
            "routes.duration,routes.distanceMeters,routes.staticDuration";

    private final RestClient restClient;
    private final GoogleRoutesProperties properties;

    public GoogleRoutesClient(RestClient.Builder builder, GoogleRoutesProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public boolean usable() {
        return properties.usable();
    }

    public List<GoogleRoute> computeRoutes(GoogleRouteQuery query) {
        if (!usable()) {
            throw new IntegrationException("Google Routes is not configured");
        }
        Map<String, Object> body = requestBody(query);
        JsonNode root;
        try {
            root = restClient.post()
                    .uri("/directions/v2:computeRoutes")
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception exception) {
            throw new IntegrationException("Google Routes request failed", exception);
        }
        JsonNode routes = root == null ? null : root.path("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            throw new IntegrationException("Google Routes returned no routes");
        }
        List<GoogleRoute> result = new ArrayList<>();
        for (JsonNode route : routes) {
            Duration duration = parseGoogleDuration(route.path("duration").asText(null));
            Duration staticDuration = route.hasNonNull("staticDuration")
                    ? parseGoogleDuration(route.path("staticDuration").asText())
                    : null;
            long distanceMeters = route.path("distanceMeters").asLong(-1);
            if (distanceMeters < 0) {
                throw new IntegrationException("Google Routes response missing distance");
            }
            result.add(new GoogleRoute(duration, staticDuration, distanceMeters));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> requestBody(GoogleRouteQuery query) {
        if (query.timeRole() == TimeRole.ARRIVE_BY && query.mode() != TravelMode.TRANSIT) {
            throw new IllegalArgumentException(
                    "Google arrivalTime is supported only for transit; other modes require Java iteration");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", waypoint(query.fromLatitude(), query.fromLongitude()));
        body.put("destination", waypoint(query.toLatitude(), query.toLongitude()));
        body.put("travelMode", query.mode().name());
        if (query.mode() == TravelMode.DRIVE || query.mode() == TravelMode.TWO_WHEELER) {
            body.put("routingPreference", "TRAFFIC_AWARE");
            body.put("computeAlternativeRoutes", true);
        }
        body.put(query.timeRole() == TimeRole.ARRIVE_BY ? "arrivalTime" : "departureTime",
                query.time().toString());
        body.put("languageCode", "zh-TW");
        body.put("units", "METRIC");
        return body;
    }

    private static Map<String, Object> waypoint(double latitude, double longitude) {
        return Map.of("location", Map.of("latLng", Map.of(
                "latitude", latitude,
                "longitude", longitude)));
    }

    private static Duration parseGoogleDuration(String value) {
        if (value == null || !value.endsWith("s")) {
            throw new IntegrationException("Google Routes response missing duration");
        }
        try {
            BigDecimal seconds = new BigDecimal(value.substring(0, value.length() - 1));
            return Duration.ofMillis(seconds.multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.CEILING)
                    .longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IntegrationException("Google Routes duration is invalid", exception);
        }
    }

    public enum TravelMode {
        DRIVE,
        TWO_WHEELER,
        WALK,
        TRANSIT
    }

    public enum TimeRole {
        DEPART_AT,
        ARRIVE_BY
    }

    public record GoogleRouteQuery(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude,
            TravelMode mode,
            TimeRole timeRole,
            Instant time) {

        public GoogleRouteQuery {
            if (mode == null || timeRole == null || time == null) {
                throw new IllegalArgumentException("mode, time role and time are required");
            }
        }
    }

    public record GoogleRoute(
            Duration duration, Duration staticDuration, long distanceMeters) {
    }
}

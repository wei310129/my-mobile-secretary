package com.aproject.aidriven.mymobilesecretary.integration.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.GoogleRouteQuery;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.TimeRole;
import com.aproject.aidriven.mymobilesecretary.integration.transport.GoogleRoutesClient.TravelMode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleRoutesClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> fieldMask = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void driveUsesTrafficAwareDepartureAndParsesAlternatives() {
        server.createContext("/directions/v2:computeRoutes", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            fieldMask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            byte[] response = ("{\"routes\":["
                    + "{\"duration\":\"1200s\",\"staticDuration\":\"900s\",\"distanceMeters\":12000},"
                    + "{\"duration\":\"1320.5s\",\"distanceMeters\":11500}]}")
                    .getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var routes = client().computeRoutes(query(TravelMode.DRIVE, TimeRole.DEPART_AT));

        assertThat(routes).hasSize(2);
        assertThat(routes.get(0).duration()).isEqualTo(Duration.ofMinutes(20));
        assertThat(routes.get(0).staticDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(routes.get(1).duration()).isEqualTo(Duration.ofMillis(1_320_500));
        assertThat(requestBody.get())
                .contains("\"travelMode\":\"DRIVE\"")
                .contains("\"routingPreference\":\"TRAFFIC_AWARE\"")
                .contains("\"computeAlternativeRoutes\":true")
                .contains("\"departureTime\"")
                .doesNotContain("\"arrivalTime\"");
        assertThat(fieldMask.get())
                .contains("routes.duration", "routes.distanceMeters", "routes.staticDuration");
    }

    @Test
    void transitArriveByUsesArrivalTimeWithoutDrivingPreference() {
        server.createContext("/directions/v2:computeRoutes", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            fieldMask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            byte[] response = ("{\"routes\":[{\"duration\":\"1800s\",\"distanceMeters\":8000,"
                    + "\"legs\":[{\"steps\":[{\"travelMode\":\"TRANSIT\",\"transitDetails\":{"
                    + "\"headsign\":\"淡水\",\"stopDetails\":{"
                    + "\"departureStop\":{\"name\":\"捷運台北車站\"},"
                    + "\"arrivalStop\":{\"name\":\"捷運中山站\"}},"
                    + "\"transitLine\":{\"name\":\"淡水信義線\",\"nameShort\":\"紅線\"}}}]}]}]}")
                    .getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var routes = client().computeRoutes(query(TravelMode.TRANSIT, TimeRole.ARRIVE_BY));

        assertThat(routes).singleElement()
                .satisfies(route -> {
                    assertThat(route.duration()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(route.transitLegs()).singleElement().satisfies(leg -> {
                        assertThat(leg.lineName()).isEqualTo("紅線");
                        assertThat(leg.headsign()).isEqualTo("淡水");
                        assertThat(leg.departureStop()).isEqualTo("捷運台北車站");
                        assertThat(leg.arrivalStop()).isEqualTo("捷運中山站");
                    });
                });
        assertThat(requestBody.get())
                .contains("\"travelMode\":\"TRANSIT\"")
                .contains("\"arrivalTime\"")
                .doesNotContain("routingPreference", "computeAlternativeRoutes", "departureTime");
        assertThat(fieldMask.get())
                .contains("routes.legs.steps.transitDetails", "routes.legs.steps.travelMode");
    }

    @Test
    void nonTransitArriveByMustBeSolvedByJavaIteration() {
        assertThatThrownBy(() -> client().computeRoutes(
                        query(TravelMode.WALK, TimeRole.ARRIVE_BY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Java iteration");
    }

    private GoogleRoutesClient client() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new GoogleRoutesClient(
                RestClient.builder(),
                new GoogleRoutesProperties(
                        true, "test-key", baseUrl, Duration.ofSeconds(2)));
    }

    private static GoogleRouteQuery query(TravelMode mode, TimeRole timeRole) {
        return new GoogleRouteQuery(
                25.0330, 121.5654, 24.9828, 121.5428,
                mode, timeRole, Instant.parse("2026-08-02T02:00:00Z"));
    }
}

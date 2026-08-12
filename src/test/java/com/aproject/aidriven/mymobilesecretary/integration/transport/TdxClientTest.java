package com.aproject.aidriven.mymobilesecretary.integration.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient.TravelQuery;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * TDX token 與路線規劃 client 測試(JDK HttpServer 假服務):
 * token 快取、旅行時間解析、認證失敗、查無路線。
 */
class TdxClientTest {

    private HttpServer server;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger routingRequests = new AtomicInteger();
    private final AtomicReference<String> routingQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        tokenRequests.set(0);
        routingRequests.set(0);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TdxProperties props() {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new TdxProperties(true, base + "/api", base + "/token", "cid", "secret", Duration.ofSeconds(2));
    }

    private void stubToken(int status, String body) {
        server.createContext("/token", exchange -> {
            tokenRequests.incrementAndGet();
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void stubRouting(int status, String body) {
        server.createContext("/api/maas/routing", exchange -> {
            routingRequests.incrementAndGet();
            routingQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private TdxRoutingClient client() {
        TdxProperties props = props();
        return new TdxRoutingClient(RestClient.builder(), props,
                new TdxTokenManager(RestClient.builder(), props, Clock.systemUTC()));
    }

    private TravelQuery query() {
        return TravelQuery.of(25.0330, 121.5654, 24.9828, 121.5428, Instant.now());
    }

    @Test
    void parsesTravelTimeSeconds() {
        stubToken(200, "{\"access_token\":\"tok\",\"expires_in\":1800}");
        stubRouting(200, "{\"data\":{\"routes\":[{\"travel_time\":5400}]}}");

        Duration duration = client().getTransitTravelTime(query());

        assertThat(duration).isEqualTo(Duration.ofSeconds(5400));
    }

    @Test
    void parsesTypedTransitLegsWithoutProviderIdentifiers() {
        stubToken(200, "{\"access_token\":\"tok\",\"expires_in\":1800}");
        stubRouting(200, """
                {"data":{"routes":[{"travel_time":2400,"sections":[
                  {"type":"drive","transport":{"mode":"YOXI","uuid":"raw-id"}},
                  {"type":"transit",
                   "departure":{"place":{"name":"捷運大坪林站"}},
                   "arrival":{"place":{"name":"捷運台北車站"}},
                   "transport":{"mode":"MRT","shortName":"松山新店線",
                                  "headsign":"松山","uuid":"raw-transit-id"}}
                ]}]}}
                """);

        var route = client().getTransitRoute(query());

        assertThat(route.duration()).isEqualTo(Duration.ofMinutes(40));
        assertThat(routingQuery.get())
                .contains(
                        "first_mile_mode=0", "first_mile_time=20",
                        "last_mile_mode=0", "last_mile_time=20");
        assertThat(route.transitLegs()).singleElement().satisfies(leg -> {
            assertThat(leg.mode()).isEqualTo("MRT");
            assertThat(leg.lineName()).isEqualTo("松山新店線");
            assertThat(leg.headsign()).isEqualTo("松山");
            assertThat(leg.departureStop()).isEqualTo("捷運大坪林站");
            assertThat(leg.arrivalStop()).isEqualTo("捷運台北車站");
            assertThat(leg.toString()).doesNotContain("raw-id", "raw-transit-id", "uuid");
        });
    }

    /** token 會被快取:連續兩次查詢只打一次 token endpoint。 */
    @Test
    void tokenIsCachedAcrossRequests() {
        stubToken(200, "{\"access_token\":\"tok\",\"expires_in\":1800}");
        stubRouting(200, "{\"data\":{\"routes\":[{\"travel_time\":600}]}}");
        TdxRoutingClient client = client();

        client.getTransitTravelTime(query());
        client.getTransitTravelTime(TravelQuery.of(25.05, 121.52, 24.99, 121.54, Instant.now()));

        assertThat(routingRequests.get()).isEqualTo(2);
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void tokenFailureThrowsIntegrationException() {
        stubToken(401, "{\"error\":\"invalid_client\"}");

        assertThatThrownBy(() -> client().getTransitTravelTime(query()))
                .isInstanceOf(IntegrationException.class);
    }

    /** 查無路線(偏遠地區)→ 明確錯誤,呼叫端 fallback。 */
    @Test
    void emptyRoutesThrowsIntegrationException() {
        stubToken(200, "{\"access_token\":\"tok\",\"expires_in\":1800}");
        stubRouting(200, "{\"data\":{\"routes\":[]}}");

        assertThatThrownBy(() -> client().getTransitTravelTime(query()))
                .isInstanceOf(IntegrationException.class);
    }
}

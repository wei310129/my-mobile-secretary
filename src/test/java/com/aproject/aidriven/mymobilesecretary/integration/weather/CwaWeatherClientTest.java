package com.aproject.aidriven.mymobilesecretary.integration.weather;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 氣象署 client 測試:用 JDK HttpServer 當假外部服務,
 * 涵蓋成功解析、非 2xx、格式錯誤、查無縣市、timeout。
 */
class CwaWeatherClientTest {

    private static final String OK_JSON = """
            {"records":{"location":[{"locationName":"新北市","weatherElement":[
              {"elementName":"Wx","time":[{"parameter":{"parameterName":"多雲時晴"}}]},
              {"elementName":"PoP","time":[{"parameter":{"parameterName":"30"}}]},
              {"elementName":"MinT","time":[{"parameter":{"parameterName":"26"}}]},
              {"elementName":"MaxT","time":[{"parameter":{"parameterName":"34"}}]}]}]}}
            """;

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CwaWeatherClient client(Duration timeout) {
        CwaProperties props = new CwaProperties(
                "http://localhost:" + server.getAddress().getPort() + "/api", "test-key", timeout);
        return new CwaWeatherClient(RestClient.builder(), props);
    }

    private void respondWith(int status, String body) {
        server.createContext("/api/v1/rest/datastore/F-C0032-001", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void parsesForecastFields() {
        respondWith(200, OK_JSON);

        WeatherForecast forecast = client(Duration.ofSeconds(2)).getForecast("新北市");

        assertThat(forecast.county()).isEqualTo("新北市");
        assertThat(forecast.description()).isEqualTo("多雲時晴");
        assertThat(forecast.rainProbabilityPercent()).isEqualTo(30);
        assertThat(forecast.minTemp()).isEqualTo(26);
        assertThat(forecast.maxTemp()).isEqualTo(34);
    }

    @Test
    void non2xxThrowsIntegrationException() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getForecast("新北市"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void malformedBodyThrowsIntegrationException() {
        respondWith(200, "not json at all");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getForecast("新北市"))
                .isInstanceOf(IntegrationException.class);
    }

    /** 查無縣市(location 空陣列)也要是明確錯誤,不能回一半的資料。 */
    @Test
    void emptyLocationThrowsIntegrationException() {
        respondWith(200, "{\"records\":{\"location\":[]}}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getForecast("火星市"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void timeoutThrowsIntegrationException() {
        server.createContext("/api/v1/rest/datastore/F-C0032-001", exchange -> {
            try {
                Thread.sleep(1500); // 超過 client 的 500ms read timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        assertThatThrownBy(() -> client(Duration.ofMillis(500)).getForecast("新北市"))
                .isInstanceOf(IntegrationException.class);
    }
}

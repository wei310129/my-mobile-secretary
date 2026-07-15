package com.aproject.aidriven.mymobilesecretary.integration.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Google Places client 測試(JDK HttpServer 假服務):
 * 解析成功、查無結果回 empty、認證失敗丟明確錯誤——不打真實 Google。
 */
class GooglePlacesClientTest {

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

    private GooglePlacesClient client() {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new GooglePlacesClient(RestClient.builder(),
                new GooglePlacesProperties(true, "test-key", base, Duration.ofSeconds(2)));
    }

    private void stubSearch(int status, String body) {
        server.createContext("/v1/places:searchText", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void parsesFirstCandidate() {
        stubSearch(200, """
                {"places":[{
                  "displayName": {"text": "全聯福利中心 新店民權店"},
                  "formattedAddress": "231台灣新北市新店區民權路42號",
                  "location": {"latitude": 24.9676, "longitude": 121.5407},
                  "primaryTypeDisplayName": {"text": "超市"}
                }]}
                """);

        Optional<GooglePlacesClient.PlaceCandidate> candidate = client().searchFirst("全聯 新店");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().name()).isEqualTo("全聯福利中心 新店民權店");
        assertThat(candidate.get().address()).contains("民權路42號");
        assertThat(candidate.get().latitude()).isEqualTo(24.9676);
        assertThat(candidate.get().type()).isEqualTo("超市");
    }

    /** Google 查無結果(空 body 或空 places)→ empty,由呼叫端決定訊息。 */
    @Test
    void noResultsReturnsEmpty() {
        stubSearch(200, "{}");

        assertThat(client().searchFirst("不存在的店")).isEmpty();
    }

    @Test
    void authFailureThrowsIntegrationException() {
        stubSearch(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");

        assertThatThrownBy(() -> client().searchFirst("全聯"))
                .isInstanceOf(IntegrationException.class);
    }
}

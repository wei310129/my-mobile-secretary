package com.aproject.aidriven.mymobilesecretary.integration.line;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * LINE 內容下載 client 測試(JDK HttpServer 假服務):
 * 成功取回 bytes 與 MIME、非 2xx 與空內容都要丟明確錯誤讓收據流程決定回覆。
 */
class LineContentClientTest {

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

    private LineContentClient client() {
        String base = "http://localhost:" + server.getAddress().getPort();
        LineProperties props = new LineProperties(true, "cid", "secret", "static-token", "",
                base, base, base + "/token", Duration.ofSeconds(2));
        return new LineContentClient(RestClient.builder(), props,
                new LineTokenManager(RestClient.builder(), props, Clock.systemUTC()));
    }

    private void stubContent(int status, byte[] body, String contentType) {
        server.createContext("/v2/bot/message/", exchange -> {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
    }

    @Test
    void fetchesBytesAndMimeType() {
        stubContent(200, "fake-image-bytes".getBytes(UTF_8), "image/png");

        LineContentClient.MessageContent content = client().fetchContent("msg-1");

        assertThat(content.bytes()).isEqualTo("fake-image-bytes".getBytes(UTF_8));
        assertThat(content.mimeType()).isEqualTo("image/png");
    }

    /** 沒有 Content-Type 標頭 → 預設 image/jpeg(LINE 圖片實務上都是 JPEG)。 */
    @Test
    void missingContentTypeDefaultsToJpeg() {
        stubContent(200, "x".getBytes(UTF_8), null);

        assertThat(client().fetchContent("msg-1").mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void non2xxThrowsIntegrationException() {
        stubContent(404, "{}".getBytes(UTF_8), "application/json");

        assertThatThrownBy(() -> client().fetchContent("msg-404"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void emptyBodyThrowsIntegrationException() {
        stubContent(200, new byte[0], "image/jpeg");

        assertThatThrownBy(() -> client().fetchContent("msg-empty"))
                .isInstanceOf(IntegrationException.class);
    }
}

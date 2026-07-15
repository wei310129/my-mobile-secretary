package com.aproject.aidriven.mymobilesecretary.integration.line;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * LINE 回覆 client 與 token 管理測試(JDK HttpServer 假服務):
 * 長效 token 直用、stateless token 換發與快取、token 失敗與回覆失敗都不得往外拋
 * ——LINE 回覆只是告知結果,通道故障不能讓已完成的操作看起來像失敗。
 */
class LineMessagingClientTest {

    private HttpServer server;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final List<String> replyAuthHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        tokenRequests.set(0);
        replyAuthHeaders.clear();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private LineProperties props(String staticAccessToken) {
        return new LineProperties(true, "channel-id", "channel-secret", staticAccessToken, "",
                baseUrl(), baseUrl(), baseUrl() + "/oauth2/v3/token", Duration.ofSeconds(2));
    }

    private void stubToken(int status, String body) {
        server.createContext("/oauth2/v3/token", exchange -> {
            tokenRequests.incrementAndGet();
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void stubReply(int status) {
        server.createContext("/v2/bot/message/reply", exchange -> {
            replyAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private LineMessagingClient client(LineProperties props) {
        return new LineMessagingClient(RestClient.builder(), props,
                new LineTokenManager(RestClient.builder(), props, Clock.systemUTC()));
    }

    /** 設定了長效 token 就直接用,不打 token endpoint。 */
    @Test
    void staticAccessTokenIsUsedDirectly() {
        stubReply(200);
        client(props("long-lived-token")).reply("rt-1", "已記下");

        assertThat(replyAuthHeaders).containsExactly("Bearer long-lived-token");
        assertThat(tokenRequests.get()).isZero();
    }

    /** 沒有長效 token → 用 channel-id/secret 換發 stateless token,並在效期內快取。 */
    @Test
    void statelessTokenIsFetchedAndCachedAcrossReplies() {
        stubToken(200, "{\"token_type\":\"Bearer\",\"access_token\":\"stateless-tok\",\"expires_in\":900}");
        stubReply(200);
        LineMessagingClient client = client(props(""));

        client.reply("rt-1", "第一則");
        client.reply("rt-2", "第二則");

        assertThat(replyAuthHeaders).containsExactly("Bearer stateless-tok", "Bearer stateless-tok");
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    /** token 換發失敗(如憑證錯誤)→ reply 只記錄,不得往外拋。 */
    @Test
    void tokenFailureDoesNotPropagate() {
        stubToken(401, "{\"error\":\"invalid_client\"}");
        stubReply(200);

        assertThatCode(() -> client(props("")).reply("rt-1", "hello")).doesNotThrowAnyException();
        assertThat(replyAuthHeaders).isEmpty();
    }

    /** LINE 回覆 API 非 2xx(如 reply token 過期)→ 同樣不得往外拋。 */
    @Test
    void replyFailureDoesNotPropagate() {
        stubReply(400);

        assertThatCode(() -> client(props("long-lived-token")).reply("expired-rt", "hello"))
                .doesNotThrowAnyException();
    }
}

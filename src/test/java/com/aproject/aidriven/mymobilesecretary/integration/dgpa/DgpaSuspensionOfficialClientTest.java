package com.aproject.aidriven.mymobilesecretary.integration.dgpa;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DgpaSuspensionOfficialClientTest {
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void confirmsOnlyWhenOfficialPageContainsDateRegionAndStatus() {
        stub("<html><body>115年 7月 9日 臺北市 停止上班、停止上課</body></html>");
        var client = client();

        var result = client.verify(LocalDate.of(2026, 7, 9),
                "臺北市：停止上班、停止上課");

        assertThat(result.reachable()).isTrue();
        assertThat(result.officialMatch()).isTrue();
    }

    @Test
    void reachablePageWithoutSameDateIsNotTreatedAsConfirmation() {
        stub("<html><body>115年 7月 10日 臺北市 停止上班、停止上課</body></html>");

        var result = client().verify(LocalDate.of(2026, 7, 9),
                "臺北市：停止上班、停止上課");

        assertThat(result.reachable()).isTrue();
        assertThat(result.officialMatch()).isFalse();
        assertThat(result.summary()).contains("未找到", "未獲官方頁面證實");
    }

    private DgpaSuspensionOfficialClient client() {
        return new DgpaSuspensionOfficialClient(RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort() + "/official");
    }

    private void stub(String body) {
        server.createContext("/official", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}

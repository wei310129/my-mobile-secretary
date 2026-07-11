package com.aproject.aidriven.mymobilesecretary.integration.weather;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 天氣快取整合測試(真 Redis):同縣市查兩次,外部 API 只被打一次。
 * 位置事件一天數十次觸發評估,不快取會把免費額度燒光。
 */
class WeatherCacheIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger upstreamHits = new AtomicInteger();
    private static HttpServer fakeCwa;

    /** 假氣象署要在 Spring context 建立前就绪,才能把 base-url 指過來。 */
    @DynamicPropertySource
    static void fakeCwaServer(DynamicPropertyRegistry registry) throws Exception {
        fakeCwa = HttpServer.create(new InetSocketAddress(0), 0);
        fakeCwa.createContext("/api/v1/rest/datastore/F-C0032-001", exchange -> {
            upstreamHits.incrementAndGet();
            byte[] bytes = """
                    {"records":{"location":[{"locationName":"新北市","weatherElement":[
                      {"elementName":"Wx","time":[{"parameter":{"parameterName":"晴"}}]},
                      {"elementName":"PoP","time":[{"parameter":{"parameterName":"10"}}]},
                      {"elementName":"MinT","time":[{"parameter":{"parameterName":"27"}}]},
                      {"elementName":"MaxT","time":[{"parameter":{"parameterName":"35"}}]}]}]}}
                    """.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fakeCwa.start();
        registry.add("app.integration.cwa.base-url",
                () -> "http://localhost:" + fakeCwa.getAddress().getPort() + "/api");
    }

    @AfterAll
    static void stopServer() {
        fakeCwa.stop(0);
    }

    @Autowired
    private CwaWeatherClient weatherClient;

    @Test
    void secondCallHitsCacheNotUpstream() {
        int before = upstreamHits.get();

        WeatherForecast first = weatherClient.getForecast("新北市");
        WeatherForecast second = weatherClient.getForecast("新北市");

        assertThat(first).isEqualTo(second);
        assertThat(first.rainProbabilityPercent()).isEqualTo(10);
        // 第二次走 Redis 快取,外部只被打一次
        assertThat(upstreamHits.get() - before).isEqualTo(1);
    }
}

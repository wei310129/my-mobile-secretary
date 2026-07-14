package com.aproject.aidriven.mymobilesecretary;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptInterpreter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 整合測試用的真實基礎設施:PostGIS 與 Redis 容器。
 *
 * 關鍵規則:@ServiceConnection 會自動把容器連線資訊注入 Spring,
 * 測試設定檔(application-test.yaml)不寫死任何連線資訊。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** 用 PostGIS 映像取代純 PostgreSQL,讓 Flyway 的 CREATE EXTENSION postgis 可在測試中執行。 */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    }

    // GenericContainer 無法從映像名自動推斷服務種類,必須指定 name = "redis"
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    /**
     * 測試用意圖解析器:不打真實 LLM,由測試預先塞入下一個回覆。
     * 沒塞就丟例外——順便驗證 IntentService 的 fallback 路徑。
     */
    public static class StubIntentInterpreter implements IntentInterpreter {

        private final AtomicReference<IntentCommand> next = new AtomicReference<>();

        /** 測試呼叫:設定下一次 interpret 的回傳。 */
        public void nextCommand(IntentCommand command) {
            next.set(command);
        }

        @Override
        public IntentCommand interpret(String text, Instant now) {
            IntentCommand command = next.getAndSet(null);
            if (command == null) {
                throw new IllegalStateException("stub has no command (simulates LLM failure)");
            }
            return command;
        }
    }

    @Bean
    StubIntentInterpreter stubIntentInterpreter() {
        return new StubIntentInterpreter();
    }

    /**
     * 測試用收據解析器:不打真實多模態 LLM,由測試預先塞入下一個回覆。
     * 沒塞就丟例外——順便驗證 ReceiptService 的失敗回覆路徑。
     */
    public static class StubReceiptInterpreter implements ReceiptInterpreter {

        private final AtomicReference<ReceiptCommand> next = new AtomicReference<>();

        /** 測試呼叫:設定下一次 interpret 的回傳。 */
        public void nextCommand(ReceiptCommand command) {
            next.set(command);
        }

        @Override
        public ReceiptCommand interpret(byte[] imageBytes, String mimeType) {
            ReceiptCommand command = next.getAndSet(null);
            if (command == null) {
                throw new IllegalStateException("stub has no receipt (simulates LLM failure)");
            }
            return command;
        }
    }

    @Bean
    StubReceiptInterpreter stubReceiptInterpreter() {
        return new StubReceiptInterpreter();
    }
}

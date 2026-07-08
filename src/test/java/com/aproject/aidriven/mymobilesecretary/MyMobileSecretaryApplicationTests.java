package com.aproject.aidriven.mymobilesecretary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 0 驗收測試:Spring context 可啟動(含 Flyway migration 對 PostGIS 執行),
 * 且 /actuator/health 對 DB 與 Redis 都回報健康。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MyMobileSecretaryApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    /** context 能起來就代表:依賴解析、Flyway migration、DB/Redis 連線全部成功。 */
    @Test
    void contextLoads() {
    }

    /** health endpoint 必須回 200 UP——DB 或 Redis 任一連不上都會變 DOWN。 */
    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}

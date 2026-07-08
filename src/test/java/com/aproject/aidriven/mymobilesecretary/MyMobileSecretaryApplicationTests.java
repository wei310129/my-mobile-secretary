package com.aproject.aidriven.mymobilesecretary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * Phase 0 驗收測試:Spring context 可啟動(含 Flyway migration 對 PostGIS 執行),
 * 且 /actuator/health 對 DB 與 Redis 都回報健康。
 */
class MyMobileSecretaryApplicationTests extends IntegrationTestBase {

    /** context 能起來就代表:依賴解析、Flyway migration、DB/Redis 連線全部成功。 */
    @Test
    void contextLoads() {
    }

    /** health endpoint 必須回 200 UP——DB 或 Redis 任一連不上都會變 DOWN。 */
    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}

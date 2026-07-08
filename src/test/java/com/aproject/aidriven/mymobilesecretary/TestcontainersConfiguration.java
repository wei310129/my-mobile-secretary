package com.aproject.aidriven.mymobilesecretary;

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
}

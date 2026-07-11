package com.aproject.aidriven.mymobilesecretary.integration.weather;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 中央氣象署開放資料平台設定。api-key 放 secrets.yaml(gitignored)。
 */
@ConfigurationProperties(prefix = "app.integration.cwa")
public record CwaProperties(
        @DefaultValue("https://opendata.cwa.gov.tw/api") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("5s") Duration timeout
) {
}

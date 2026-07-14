package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * LINE channel access token 管理。
 *
 * 設定了長效 channel-access-token 就直接用(測試環境走這條,不打 token endpoint);
 * 否則用 channel-id + channel-secret 向 LINE 換發 stateless token
 * (client credentials 流程,效期 15 分鐘、無發行次數限制,LINE 官方建議做法)。
 *
 * token 進程內快取,到期前 60 秒視為過期提早換新;
 * synchronized 避免並發時重複打 token endpoint。
 */
@Component
public class LineTokenManager {

    private final RestClient restClient;
    private final LineProperties properties;
    private final Clock clock;

    /** 快取的 token 與其過期時間;volatile 讓讀取不用鎖。 */
    private volatile CachedToken cached;

    public LineTokenManager(RestClient.Builder builder, LineProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder.requestFactory(factory).build();
    }

    /** 取得有效 token;長效 token 優先,否則從快取或 LINE token endpoint 取 stateless token。 */
    public String getAccessToken() {
        if (!properties.channelAccessToken().isBlank()) {
            return properties.channelAccessToken();
        }
        CachedToken current = cached;
        Instant now = Instant.now(clock);
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.token();
        }
        synchronized (this) {
            // 進鎖後再檢查一次:可能別的執行緒剛換好
            current = cached;
            if (current != null && Instant.now(clock).isBefore(current.expiresAt())) {
                return current.token();
            }
            CachedToken fresh = fetchToken();
            cached = fresh;
            return fresh.token();
        }
    }

    private CachedToken fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.channelId());
        form.add("client_secret", properties.channelSecret());

        JsonNode body;
        try {
            body = restClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IntegrationException("LINE token request failed", e);
        }
        if (body == null || body.path("access_token").isMissingNode()) {
            throw new IntegrationException("LINE token response missing access_token");
        }
        // 到期前 60 秒視為過期,避免用到剛好過期的 token(stateless token 效期 900 秒)
        long expiresIn = body.path("expires_in").asLong(900);
        Instant expiresAt = Instant.now(clock).plusSeconds(Math.max(expiresIn - 60, 60));
        return new CachedToken(body.get("access_token").asText(), expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}

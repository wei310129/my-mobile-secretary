package com.aproject.aidriven.mymobilesecretary.integration.transport;

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
 * TDX OAuth2 access token 管理(client credentials 流程)。
 *
 * token 進程內快取,到期前 60 秒視為過期提早換新;
 * synchronized 避免並發時重複打 token endpoint。
 */
@Component
public class TdxTokenManager {

    private final RestClient restClient;
    private final TdxProperties properties;
    private final Clock clock;

    /** 快取的 token 與其過期時間;volatile 讓讀取不用鎖。 */
    private volatile CachedToken cached;

    public TdxTokenManager(RestClient.Builder builder, TdxProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder.requestFactory(factory).build();
    }

    /** 取得有效 token;快取有效就直接回,否則向 TDX 換新。 */
    public String getAccessToken() {
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
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        JsonNode body;
        try {
            body = restClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IntegrationException("TDX token request failed", e);
        }
        if (body == null || body.path("access_token").isMissingNode()) {
            throw new IntegrationException("TDX token response missing access_token");
        }
        // 到期前 60 秒視為過期,避免用到剛好過期的 token
        long expiresIn = body.path("expires_in").asLong(1800);
        Instant expiresAt = Instant.now(clock).plusSeconds(Math.max(expiresIn - 60, 60));
        return new CachedToken(body.get("access_token").asText(), expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}

package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * LINE Messaging API 回覆訊息 client。
 *
 * 關鍵規則:回覆失敗只記錄,不得往外丟——使用者的話已經處理完成(任務/行程已建立),
 * LINE 回覆只是告知結果,通道故障不能讓已完成的操作看起來像失敗。
 */
@Component
public class LineMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(LineMessagingClient.class);

    private final RestClient restClient;
    private final RestClient loadingRestClient;
    private final LineTokenManager tokenManager;

    public LineMessagingClient(RestClient.Builder builder, LineProperties properties, LineTokenManager tokenManager) {
        this.tokenManager = tokenManager;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(factory)
                .build();
        SimpleClientHttpRequestFactory loadingFactory = new SimpleClientHttpRequestFactory();
        int loadingTimeout = (int) Math.min(properties.timeout().toMillis(), 500L);
        loadingFactory.setConnectTimeout(loadingTimeout);
        loadingFactory.setReadTimeout(loadingTimeout);
        this.loadingRestClient = builder.clone()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(loadingFactory)
                .build();
    }

    /** Displays LINE's one-to-one loading animation without consuming the one-time reply token. */
    public boolean startLoading(String chatId) {
        if (chatId == null || chatId.isBlank()) return false;
        try {
            loadingRestClient.post()
                    .uri("/v2/bot/chat/loading/start")
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chatId", chatId, "loadingSeconds", 5))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception exception) {
            log.warn("LINE loading animation failed [cause={}]",
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 用 reply token 回覆一則文字訊息;reply token 一次性且短效,失敗不重試。
     * 取 token 失敗(換發 stateless token 出錯)也走同一個 catch——回覆通道故障不得外洩。
     */
    public Optional<String> reply(String replyToken, String text) {
        try {
            ReplyResponse response = restClient.post()
                    .uri("/v2/bot/message/reply")
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "replyToken", replyToken,
                            "messages", List.of(Map.of("type", "text", "text", text))))
                    .retrieve()
                    .body(ReplyResponse.class);
            return response == null || response.sentMessages() == null
                    ? Optional.empty()
                    : response.sentMessages().stream()
                            .filter(java.util.Objects::nonNull)
                            .map(SentMessage::id)
                            .filter(id -> id != null && !id.isBlank())
                            .findFirst();
        } catch (Exception e) {
            // Do not log the one-time reply token, message body, access token, or a large stack.
            log.warn("LINE reply failed [cause={}]", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Sends a proactive message with LINE's retry-key deduplication contract. */
    public boolean push(String userId, String text, UUID deliveryId) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank() || deliveryId == null) {
            return false;
        }
        try {
            restClient.post()
                    .uri("/v2/bot/message/push")
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .header("X-Line-Retry-Key", deliveryId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "to", userId,
                            "messages", List.of(Map.of("type", "text", "text", text))))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException responseFailure) {
            if (responseFailure.getStatusCode().value() == 409) {
                return true;
            }
            log.warn("LINE push failed [status={}]", responseFailure.getStatusCode().value());
            return false;
        } catch (RuntimeException failure) {
            log.warn("LINE push failed [cause={}]", failure.getClass().getSimpleName());
            return false;
        }
    }

    record ReplyResponse(List<SentMessage> sentMessages) {
    }

    record SentMessage(String id, String quoteToken) {
    }
}

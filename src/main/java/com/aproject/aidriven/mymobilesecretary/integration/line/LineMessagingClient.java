package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
    private final LineProperties properties;

    public LineMessagingClient(RestClient.Builder builder, LineProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 用 reply token 回覆一則文字訊息;reply token 一次性且短效,失敗不重試。 */
    public void reply(String replyToken, String text) {
        try {
            restClient.post()
                    .uri("/v2/bot/message/reply")
                    .header("Authorization", "Bearer " + properties.channelAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "replyToken", replyToken,
                            "messages", List.of(Map.of("type", "text", "text", text))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("LINE reply failed", new IntegrationException("LINE reply failed", e));
        }
    }
}

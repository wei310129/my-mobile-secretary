package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * LINE 訊息內容下載 client(圖片等二進位;網域是 api-data.line.me,與訊息 API 不同台)。
 *
 * 與回覆不同,抓不到內容要「讓呼叫端知道」——收據解析沒有圖就沒得做,
 * 所以失敗丟 IntegrationException,由收據流程決定怎麼回覆使用者。
 */
@Component
public class LineContentClient {

    private final RestClient restClient;
    private final LineTokenManager tokenManager;

    public LineContentClient(RestClient.Builder builder, LineProperties properties,
                             LineTokenManager tokenManager) {
        this.tokenManager = tokenManager;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.contentBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 下載一則訊息的二進位內容與其 MIME type。 */
    public MessageContent fetchContent(String messageId) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri("/v2/bot/message/{messageId}/content", messageId)
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new IntegrationException("LINE content is empty [messageId=%s]".formatted(messageId));
            }
            MediaType contentType = response.getHeaders().getContentType();
            return new MessageContent(body,
                    contentType == null ? MediaType.IMAGE_JPEG_VALUE : contentType.toString());
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException("LINE content fetch failed [messageId=%s]".formatted(messageId), e);
        }
    }

    /** 訊息內容與其 MIME type(如 image/jpeg)。 */
    public record MessageContent(byte[] bytes, String mimeType) {
    }
}

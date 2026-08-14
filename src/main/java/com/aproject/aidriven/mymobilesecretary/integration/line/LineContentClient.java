package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
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

    private static final long DEFAULT_MAX_BYTES = 15L * 1024 * 1024;

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
        return fetchContent(messageId, DEFAULT_MAX_BYTES, MediaType.IMAGE_JPEG_VALUE);
    }

    /** Reads LINE content with a strict byte cap before retaining it in application memory. */
    public MessageContent fetchContent(String messageId, long maxBytes) {
        return fetchContent(messageId, maxBytes, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private MessageContent fetchContent(String messageId, long maxBytes, String defaultMimeType) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("LINE content limit must be positive");
        }
        try {
            return restClient.get()
                    .uri("/v2/bot/message/{messageId}/content", messageId)
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IntegrationException("LINE content fetch returned non-success status");
                        }
                        long declaredLength = response.getHeaders().getContentLength();
                        if (declaredLength > maxBytes) {
                            throw new IntegrationException("LINE content exceeds the configured size limit");
                        }
                        byte[] body = readBounded(response.getBody(), maxBytes);
                        if (body.length == 0) {
                            throw new IntegrationException("LINE content is empty");
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        return new MessageContent(
                                body,
                                contentType == null ? defaultMimeType : contentType.toString());
                    });
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException("LINE content fetch failed", e);
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        int initialSize = (int) Math.min(maxBytes, 8192);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IntegrationException("LINE content exceeds the configured size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** 訊息內容與其 MIME type(如 image/jpeg)。 */
    public record MessageContent(byte[] bytes, String mimeType) {
    }
}

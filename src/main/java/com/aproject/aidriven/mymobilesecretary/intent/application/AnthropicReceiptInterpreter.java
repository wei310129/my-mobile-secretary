package com.aproject.aidriven.mymobilesecretary.intent.application;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Spring AI + Claude 多模態的收據解析器(使用者 2026-07-14 拍板允許多模態)。
 *
 * 鐵律不變:LLM 只做「理解」——把照片轉成結構化收據;
 * 存什麼、怎麼連結品項知識庫,由 ReceiptService 驗證後以確定性規則處理。
 */
@Component
@ConditionalOnProperty(prefix = "app.receipt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnthropicReceiptInterpreter implements ReceiptInterpreter {

    private static final String SYSTEM_PROMPT = """
            你是收據解析器。從使用者傳來的收據/發票照片抽出結構化資料,只輸出符合 schema 的 JSON。

            規則:
            - items:逐行品項。name 用照片上的品名(可正規化明顯縮寫,如「衛?紙」→「衛生紙」);
              price 是該品項單價(台幣整數);quantity 沒印就 1。
            - 折扣、載具、統編、總計、找零這些「不是商品」的行,不要放進 items。
            - 讀不出價格的品項直接略過,不要猜數字。
            - storeName:看得出店名才填。
            - purchasedAt:照片上有日期才填,格式 yyyy-MM-dd。
            - 這張照片若根本不是收據,items 回空陣列。
            """;

    private final ChatClient chatClient;

    public AnthropicReceiptInterpreter(ChatModel chatModel) {
        this.chatClient = ChatClient.create(chatModel);
    }

    @Override
    public ReceiptCommand interpret(byte[] imageBytes, String mimeType) {
        MimeType type = parseMimeType(mimeType);
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(user -> user
                        .text("請解析這張收據。")
                        .media(new Media(type, new ByteArrayResource(imageBytes))))
                .call()
                .entity(ReceiptCommand.class);
    }

    /** MIME 解析失敗就當 JPEG(LINE 圖片訊息實務上都是 JPEG)。 */
    private static MimeType parseMimeType(String mimeType) {
        try {
            return MimeTypeUtils.parseMimeType(mimeType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
    }
}

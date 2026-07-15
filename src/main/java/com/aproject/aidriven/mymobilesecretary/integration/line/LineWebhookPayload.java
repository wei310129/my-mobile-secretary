package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * LINE webhook 送來的事件包(只取我們用得到的欄位,其餘忽略)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LineWebhookPayload(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, String replyToken, Message message, Source source) {

        /** 是否為使用者傳來的純文字訊息。 */
        public boolean isTextMessage() {
            return "message".equals(type) && message != null && "text".equals(message.type());
        }

        /** 是否為圖片訊息(收據照片解析用;圖片內容要另打 content API 抓)。 */
        public boolean isImageMessage() {
            return "message".equals(type) && message != null && "image".equals(message.type());
        }

        /** 發訊者的 LINE userId;LINE 平台驗證請求等無來源事件為 null。 */
        public String sourceUserId() {
            return source == null ? null : source.userId();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String userId) {
    }
}

package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * LINE webhook 送來的事件包(只取我們用得到的欄位,其餘忽略)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LineWebhookPayload(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, String replyToken, Message message) {

        /** 是否為使用者傳來的純文字訊息(目前只處理這種)。 */
        public boolean isTextMessage() {
            return "message".equals(type) && message != null && "text".equals(message.type());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String type, String text) {
    }
}

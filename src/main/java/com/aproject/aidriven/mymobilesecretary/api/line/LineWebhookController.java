package com.aproject.aidriven.mymobilesecretary.api.line;

import com.aproject.aidriven.mymobilesecretary.integration.line.LineContentClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineOwnerGuard;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineProperties;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineSignatureVerifier;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineWebhookPayload;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LINE webhook 入口:轉傳訊息 → 意圖解析 → 建任務/行程 → 回覆結果。
 *
 * 這是 architecture.md §11 的「LINE 沒有讓第三方直接讀訊息的 API,轉傳是正規解法」——
 * 使用者手動把訊息轉傳給官方帳號 bot,bot 收到後走跟 say.ps1 一樣的 IntentService 流程。
 *
 * 關鍵規則:一律回 200(LINE 平台對非 200 會重送,重送會造成重複處理);
 * 驗簽失敗回 401 是唯一例外——那代表請求根本不是 LINE 發的。
 */
@RestController
@RequestMapping("/api/line")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final LineMessagingClient messagingClient;
    private final LineContentClient contentClient;
    private final LineProperties properties;
    private final IntentService intentService;
    private final ReceiptService receiptService;
    private final LineMessageLogService messageLogService;
    private final LineOwnerGuard ownerGuard;
    private final ObjectMapper objectMapper;

    public LineWebhookController(LineSignatureVerifier signatureVerifier,
                                 LineMessagingClient messagingClient,
                                 LineContentClient contentClient,
                                 LineProperties properties,
                                 IntentService intentService,
                                 ReceiptService receiptService,
                                 LineMessageLogService messageLogService,
                                 LineOwnerGuard ownerGuard,
                                 ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.messagingClient = messagingClient;
        this.contentClient = contentClient;
        this.properties = properties;
        this.intentService = intentService;
        this.receiptService = receiptService;
        this.messageLogService = messageLogService;
        this.ownerGuard = ownerGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收 LINE webhook。用 raw byte[] 而不是自動反序列化的 DTO,
     * 因為簽章驗證必須對「未被框架動過」的原始 body 計算,順序不能顛倒。
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = "X-Line-Signature", required = false) String signature,
                                        @RequestBody byte[] rawBody) {
        if (!properties.usable()) {
            // 尚未設定 channel secret/token:不驗簽(驗不了)、直接吞掉,避免誤判成攻擊
            return ResponseEntity.ok().build();
        }
        if (!signatureVerifier.verify(rawBody, signature, properties.channelSecret())) {
            log.warn("LINE webhook signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LineWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, LineWebhookPayload.class);
        } catch (Exception e) {
            // 格式壞掉也回 200——這是 LINE 平台驗證請求或未知事件,不是我方要處理的錯誤
            log.warn("LINE webhook payload unparsable", e);
            return ResponseEntity.ok().build();
        }

        for (LineWebhookPayload.Event event : payload.events()) {
            // 單人設計:非擁有者的訊息不處理也不回覆(隱私),只留底供擁有者查證
            if ((event.isTextMessage() || event.isImageMessage())
                    && !ownerGuard.allows(event.sourceUserId())) {
                messageLogService.recordSafely(LineMessageLog.Direction.IN, "BLOCKED",
                        "[非擁有者 %s] %s".formatted(event.sourceUserId(),
                                event.isTextMessage() ? event.message().text() : "(圖片)"));
                continue;
            }
            if (event.isTextMessage()) {
                handleTextMessage(event);
            } else if (event.isImageMessage()) {
                handleImageMessage(event);
            }
        }
        return ResponseEntity.ok().build();
    }

    /** 單則文字訊息:走跟 /api/intent 相同的意圖處理,結果回覆給使用者;進出訊息都留底。 */
    private void handleTextMessage(LineWebhookPayload.Event event) {
        messageLogService.recordSafely(LineMessageLog.Direction.IN, "TEXT", event.message().text());
        IntentResult result = intentService.handle(event.message().text());
        messagingClient.reply(event.replyToken(), result.message());
        messageLogService.recordSafely(LineMessageLog.Direction.OUT, "TEXT", result.message());
    }

    /**
     * 圖片訊息 = 收據照片:抓內容 → 解析 → 價格入庫 → 回覆摘要。
     * 任何失敗只回覆說明,不往外拋(webhook 必須回 200,LINE 才不會重送)。
     */
    private void handleImageMessage(LineWebhookPayload.Event event) {
        messageLogService.recordSafely(LineMessageLog.Direction.IN, "IMAGE",
                "[圖片] messageId=" + event.message().id());
        String message;
        try {
            LineContentClient.MessageContent content = contentClient.fetchContent(event.message().id());
            message = receiptService.handleImage(content.bytes(), content.mimeType()).message();
        } catch (Exception e) {
            log.warn("LINE image handling failed [messageId={}]", event.message().id(), e);
            message = "圖片我拿不到或處理失敗,可以再傳一次,或改用文字告訴我。";
        }
        messagingClient.reply(event.replyToken(), message);
        messageLogService.recordSafely(LineMessageLog.Direction.OUT, "TEXT", message);
    }
}

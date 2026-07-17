package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.shared.security.PromptInjectionGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 收據編排:照片 → LLM 解析 → 逐行驗證 → 價格歷史入庫 → 回覆摘要。
 *
 * 可靠度鐵律的收據版:LLM 失敗或照片不是收據,回明確訊息請使用者改用文字,
 * 絕不讓例外往 webhook 洩漏;單行資料爛掉只跳過該行,好的照存。
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    /** 單張收據最多入庫行數:LLM 幻覺出上百行時的保險絲。 */
    private static final int MAX_LINES = 50;

    private final ObjectProvider<ReceiptInterpreter> interpreterProvider;
    private final PriceRecordService priceRecordService;
    private final Clock clock;
    private com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService
            travelItineraryDraftService;
    private TravelItineraryDraftAnswerService travelItineraryDraftAnswerService;

    public ReceiptService(ObjectProvider<ReceiptInterpreter> interpreterProvider,
                          PriceRecordService priceRecordService,
                          Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.priceRecordService = priceRecordService;
        this.clock = clock;
    }

    /** Optional setters keep direct-construction receipt unit tests source-compatible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTravelItineraryServices(
            com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService
                    draftService,
            TravelItineraryDraftAnswerService answerService) {
        this.travelItineraryDraftService = draftService;
        this.travelItineraryDraftAnswerService = answerService;
    }

    /**
     * 處理一張收據照片,回傳要回覆給使用者的結果。
     *
     * @return 一定有人類可讀 message;savedCount = 實際入庫行數
     */
    public ReceiptResult handleImage(byte[] imageBytes, String mimeType) {
        ReceiptInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            return new ReceiptResult("收據解析未啟用,請改用文字告訴我品項與價格。", 0);
        }

        ReceiptCommand command;
        try {
            command = interpreter.interpret(imageBytes, mimeType);
        } catch (Exception e) {
            // Image/model failures can repeat on webhook retries; avoid large stack-trace writes.
            log.warn("Image document interpretation failed ({})", e.getClass().getSimpleName());
            return new ReceiptResult("這張照片我解析不了,可以再拍清楚一點,或改用文字告訴我。", 0);
        }

        if (command == null) {
            return new ReceiptResult("這張照片看起來不是收據,或讀不到品項。", 0);
        }

        if (containsPromptInjection(command)) {
            log.warn("Untrusted image document rejected [reason=prompt_injection_signal]");
            return new ReceiptResult("DOCUMENT_SECURITY_REJECTED",
                    "圖片含有疑似指令或要求揭露敏感資訊的文字，為了安全沒有執行或儲存任何內容。", 0);
        }

        ReceiptCommand.DocumentType type = command.documentType();
        if (type == null) {
            type = command.items() == null || command.items().isEmpty()
                    ? ReceiptCommand.DocumentType.UNKNOWN : ReceiptCommand.DocumentType.RECEIPT;
        }
        if (type == ReceiptCommand.DocumentType.TRAVEL_ITINERARY) {
            return handleTravelItinerary(command);
        }
        if (type == ReceiptCommand.DocumentType.UNKNOWN) {
            return new ReceiptResult("UNKNOWN_IMAGE",
                    "這張圖片看起來不是收據或旅行行程表，請告訴我文件種類後再試一次。", 0);
        }
        if (command.items() == null || command.items().isEmpty()) {
            return new ReceiptResult("RECEIPT_NOT_READABLE",
                    "這張照片看起來是收據，但讀不到有效品項。", 0);
        }

        // LLM 輸出逐行驗證:名稱/價格爛的跳過,不讓一行壞資料毀掉整張收據
        LocalDate purchasedAt = parseDateOrToday(command.purchasedAt());
        List<String> savedNames = new ArrayList<>();
        for (ReceiptCommand.Line line : command.items().stream().limit(MAX_LINES).toList()) {
            if (line == null || line.name() == null || line.name().isBlank()
                    || line.price() == null || line.price() <= 0) {
                continue;
            }
            try {
                priceRecordService.record(line.name(), command.storeName(), line.price(), purchasedAt);
                savedNames.add(line.name());
            } catch (Exception e) {
                log.warn("Price record rejected [cause={}]", e.getClass().getSimpleName());
            }
        }

        if (savedNames.isEmpty()) {
            return new ReceiptResult("收據上讀不到有效的品項價格。", 0);
        }
        String store = command.storeName() == null || command.storeName().isBlank()
                ? "" : "(" + command.storeName() + ")";
        return new ReceiptResult(
                "已記下 %d 項價格%s:\n%s".formatted(savedNames.size(), store,
                        String.join("\n", savedNames.stream().limit(5).toList())
                                + (savedNames.size() > 5 ? "\n……其餘品項也已記錄" : "")),
                savedNames.size());
    }

    private ReceiptResult handleTravelItinerary(ReceiptCommand command) {
        if (travelItineraryDraftService == null || travelItineraryDraftAnswerService == null) {
            return new ReceiptResult("TRAVEL_ITINERARY_UNAVAILABLE",
                    "旅行行程表辨識目前未啟用，請先改用文字提供行程。", 0);
        }
        try {
            var draft = travelItineraryDraftService.create(command);
            return new ReceiptResult("TRAVEL_ITINERARY_DRAFTED",
                    travelItineraryDraftAnswerService.previewMessage(draft), 0);
        } catch (IllegalArgumentException e) {
            log.warn("Travel itinerary image rejected ({})", e.getClass().getSimpleName());
            return new ReceiptResult("TRAVEL_ITINERARY_NOT_READABLE",
                    "這張圖片像旅行行程表，但讀不到可核對的行程、活動或注意事項。", 0);
        }
    }

    /** 收據日期解析失敗(缺漏、格式爛)→ 當作今天(台北時間),寧可粗略不可丟棄。 */
    private static boolean containsPromptInjection(ReceiptCommand command) {
        List<String> values = new ArrayList<>();
        values.add(command.storeName());
        values.add(command.documentTitle());
        if (command.items() != null) {
            command.items().stream().filter(java.util.Objects::nonNull)
                    .map(ReceiptCommand.Line::name).forEach(values::add);
        }
        if (command.itineraryEntries() != null) {
            for (ReceiptCommand.ItineraryEntry entry : command.itineraryEntries()) {
                if (entry == null) {
                    continue;
                }
                values.add(entry.title());
                values.add(entry.placeName());
                values.add(entry.details());
            }
        }
        if (command.activities() != null) {
            values.addAll(command.activities());
        }
        if (command.notices() != null) {
            values.addAll(command.notices());
        }
        return PromptInjectionGuard.inspectExternalContent(values).suspicious();
    }

    private LocalDate parseDateOrToday(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(clock.withZone(TAIPEI));
        }
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            return LocalDate.now(clock.withZone(TAIPEI));
        }
    }

    /** 收據處理結果:回覆訊息 + 入庫行數。 */
    public record ReceiptResult(String action, String message, int savedCount) {
        public ReceiptResult(String message, int savedCount) {
            this("RECEIPT_IMPORTED", message, savedCount);
        }

        public ReceiptResult {
            message = IntentReplyFormatter.format(
                    action != null && action.startsWith("TRAVEL_ITINERARY") ? "🗺️" : "🧾",
                    message);
        }
    }
}

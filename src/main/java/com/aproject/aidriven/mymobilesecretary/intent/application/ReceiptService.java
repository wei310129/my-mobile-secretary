package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
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

    public ReceiptService(ObjectProvider<ReceiptInterpreter> interpreterProvider,
                          PriceRecordService priceRecordService,
                          Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.priceRecordService = priceRecordService;
        this.clock = clock;
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
            log.warn("Receipt interpretation failed", e);
            return new ReceiptResult("這張照片我解析不了,可以再拍清楚一點,或改用文字告訴我。", 0);
        }

        if (command == null || command.items() == null || command.items().isEmpty()) {
            return new ReceiptResult("這張照片看起來不是收據,或讀不到品項。", 0);
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
                log.warn("Price record rejected [name={}, price={}]", line.name(), line.price(), e);
            }
        }

        if (savedNames.isEmpty()) {
            return new ReceiptResult("收據上讀不到有效的品項價格。", 0);
        }
        String store = command.storeName() == null || command.storeName().isBlank()
                ? "" : "(" + command.storeName() + ")";
        return new ReceiptResult(
                "已記下 %d 項價格%s:%s".formatted(savedNames.size(), store,
                        String.join("、", savedNames.stream().limit(5).toList())
                                + (savedNames.size() > 5 ? "…" : "")),
                savedNames.size());
    }

    /** 收據日期解析失敗(缺漏、格式爛)→ 當作今天(台北時間),寧可粗略不可丟棄。 */
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
    public record ReceiptResult(String message, int savedCount) {
    }
}

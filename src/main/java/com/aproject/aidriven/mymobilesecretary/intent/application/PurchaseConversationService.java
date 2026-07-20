package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceInsightService;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Deterministic read-only follow-up for a purchase image or an explicit merchant query. */
@Service
public class PurchaseConversationService {

    private final PriceInsightService priceInsightService;

    public PurchaseConversationService(PriceInsightService priceInsightService) {
        this.priceInsightService = priceInsightService;
    }

    public Optional<IntentResult> answer(String text, String interpretationContext) {
        if (!isPurchaseTimeQuestion(text)) return Optional.empty();
        String searchable = (text == null ? "" : text) + "\n"
                + (interpretationContext == null ? "" : interpretationContext);
        return priceInsightService.lastPurchaseMentionedIn(searchable).map(last -> {
            var record = last.record();
            String store = record.getStoreName() == null || record.getStoreName().isBlank()
                    ? "店家未記錄" : record.getStoreName();
            String amount = record.getQuantity() == 1
                    ? "NT$ %,d".formatted(record.getTotalPriceTwd())
                    : "單價 NT$ %,d × %d，合計 NT$ %,d".formatted(
                            record.getPriceTwd(), record.getQuantity(),
                            record.getTotalPriceTwd());
            return IntentResult.message(IntentResult.Action.LAST_PURCHASE_INFO,
                    "你在 %s 向 %s 購買「%s」，%s。"
                            .formatted(record.getPurchasedAt(), store,
                                    record.getItemName(), amount));
        });
    }

    static boolean isPurchaseTimeQuestion(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？]", "");
        boolean purchase = normalized.contains("買") || normalized.contains("購買");
        boolean time = normalized.contains("什麼時候") || normalized.contains("何時")
                || normalized.contains("哪天") || normalized.contains("哪一天");
        return purchase && time && normalized.length() <= 80;
    }
}

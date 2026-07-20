package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceInsightService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseConversationServiceTest {

    @Mock private PriceInsightService priceInsightService;

    @Test
    void genericQuotedImageQuestionUsesVisiblePurchaseContext() {
        PriceRecord windows = PriceRecord.record(null,
                "升級至 Windows 10/11 專業版", "Microsoft", 2999,
                LocalDate.of(2024, 10, 1), Instant.parse("2024-10-01T00:00:00Z"));
        String context = "【LINE 明確引用】[圖片解析結果]\n"
                + "Microsoft 升級至 Windows 10/11 專業版；購買日 2024-10-01；總額 TWD 2,999。";
        when(priceInsightService.lastPurchaseMentionedIn("我什麼時候買的？\n" + context))
                .thenReturn(Optional.of(new PriceInsightService.LastPurchase(windows, 10)));

        IntentResult result = new PurchaseConversationService(priceInsightService)
                .answer("我什麼時候買的？", context).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.LAST_PURCHASE_INFO);
        assertThat(result.message()).contains("2024-10-01", "Microsoft",
                "升級至 Windows 10/11 專業版", "2,999");
    }

    @Test
    void explicitMerchantQuestionIsReadOnlyPurchaseQuery() {
        PriceRecord windows = PriceRecord.record(null,
                "升級至 Windows 10/11 專業版", "Microsoft", 2999,
                LocalDate.of(2024, 10, 1), Instant.parse("2024-10-01T00:00:00Z"));
        String text = "上次買 Microsoft 相關產品或服務是什麼時候";
        when(priceInsightService.lastPurchaseMentionedIn(text + "\n" + text))
                .thenReturn(Optional.of(new PriceInsightService.LastPurchase(windows, 10)));

        assertThat(new PurchaseConversationService(priceInsightService).answer(text, text))
                .hasValueSatisfying(result -> assertThat(result.message())
                        .contains("2024-10-01", "Microsoft"));
    }

    @Test
    void unrelatedWhenQuestionIsNotConsumed() {
        assertThat(new PurchaseConversationService(priceInsightService)
                .answer("我上次運動是什麼時候", "運動紀錄")).isEmpty();
    }
}

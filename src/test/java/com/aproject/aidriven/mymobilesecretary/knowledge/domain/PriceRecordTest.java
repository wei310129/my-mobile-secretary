package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 價格紀錄不變式:0 元/負價與空品名多半是解析錯誤,不能汙染價格歷史。
 */
class PriceRecordTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-07-12");

    @Test
    void recordsTrimmedNameAndFields() {
        PriceRecord record = PriceRecord.record(3L, " 鮮奶 ", "全聯", 95, DATE, NOW);

        assertThat(record.getItemName()).isEqualTo("鮮奶");
        assertThat(record.getItemId()).isEqualTo(3L);
        assertThat(record.getPriceTwd()).isEqualTo(95);
        assertThat(record.getPurchasedAt()).isEqualTo(DATE);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> PriceRecord.record(null, "  ", "全聯", 95, DATE, NOW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonPositivePriceIsRejected() {
        assertThatThrownBy(() -> PriceRecord.record(null, "鮮奶", null, 0, DATE, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PriceRecord.record(null, "鮮奶", null, -10, DATE, NOW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void storesAuditableQuantityTotalCategoryAndTags() {
        PriceRecord record = PriceRecord.record(null, "冷氣", "全國電子", 20000, 2,
                40000, ExpenseCategory.ELECTRONICS,
                Set.of("merchant:全國電子", "organization:全國電子"), DATE, NOW);

        assertThat(record.getQuantity()).isEqualTo(2);
        assertThat(record.getTotalPriceTwd()).isEqualTo(40000);
        assertThat(record.getExpenseCategory()).isEqualTo(ExpenseCategory.ELECTRONICS);
        assertThat(record.getSemanticTags()).contains("organization:全國電子");
    }

    @Test
    void invalidQuantityOrTotalIsRejected() {
        assertThatThrownBy(() -> PriceRecord.record(null, "冷氣", "全國電子", 20000,
                0, 20000, ExpenseCategory.ELECTRONICS, Set.of(), DATE, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PriceRecord.record(null, "冷氣", "全國電子", 20000,
                1, 0, ExpenseCategory.ELECTRONICS, Set.of(), DATE, NOW))
                .isInstanceOf(BusinessException.class);
    }
}

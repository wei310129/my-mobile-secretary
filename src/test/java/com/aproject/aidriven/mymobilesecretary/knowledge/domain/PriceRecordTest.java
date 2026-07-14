package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
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
}

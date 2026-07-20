package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceInsightServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-10T04:00:00Z");

    @Mock
    private PriceRecordService priceRecordService;

    private PriceInsightService service;

    @BeforeEach
    void setUp() {
        service = new PriceInsightService(priceRecordService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void summarizesRecordedUnitPricesAndLatestChange() {
        PriceRecord latest = price(120, "全聯", LocalDate.of(2030, 8, 8));
        PriceRecord previous = price(100, "家樂福", LocalDate.of(2030, 8, 1));
        PriceRecord oldest = price(140, "全聯", LocalDate.of(2030, 7, 20));
        when(priceRecordService.list("牛奶")).thenReturn(List.of(latest, previous, oldest));
        when(priceRecordService.searchByKeyword("牛奶"))
                .thenReturn(List.of(latest, previous, oldest));

        var summary = service.summary("牛奶").orElseThrow();

        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.averagePriceTwd()).isEqualTo(120);
        assertThat(summary.lowest()).isSameAs(previous);
        assertThat(summary.highest()).isSameAs(oldest);
        assertThat(summary.latestChangeTwd()).isEqualTo(20);
        assertThat(service.lastPurchase("牛奶").orElseThrow().daysAgo()).isEqualTo(2);
    }

    @Test
    void favoriteStoreBreaksCountTieByMostRecentPurchase() {
        when(priceRecordService.list("牛奶")).thenReturn(List.of(
                price(110, "家樂福", LocalDate.of(2030, 8, 9)),
                price(120, "全聯", LocalDate.of(2030, 8, 8)),
                price(100, "家樂福", LocalDate.of(2030, 7, 2)),
                price(105, "全聯", LocalDate.of(2030, 7, 5))));

        assertThat(service.favoriteStore("牛奶")).hasValueSatisfying(store -> {
            assertThat(store.storeName()).isEqualTo("家樂福");
            assertThat(store.count()).isEqualTo(2);
            assertThat(store.lastPurchasedAt()).isEqualTo(LocalDate.of(2030, 8, 9));
        });
    }

    @Test
    void emptyHistoryProducesNoInsight() {
        when(priceRecordService.list("不存在")).thenReturn(List.of());
        when(priceRecordService.searchByKeyword("不存在")).thenReturn(List.of());

        assertThat(service.lastPurchase("不存在")).isEmpty();
        assertThat(service.summary("不存在")).isEmpty();
        assertThat(service.favoriteStore("不存在")).isEmpty();
    }

    @Test
    void lastPurchaseCanMatchMerchantKeywordAndImageContext() {
        PriceRecord windows = PriceRecord.record(null,
                "升級至 Windows 10/11 專業版", "Microsoft", 2999,
                LocalDate.of(2024, 10, 1), NOW);
        when(priceRecordService.searchByKeyword("Microsoft")).thenReturn(List.of(windows));
        when(priceRecordService.list(null)).thenReturn(List.of(windows));

        assertThat(service.lastPurchase("Microsoft")).hasValueSatisfying(last ->
                assertThat(last.record()).isSameAs(windows));
        assertThat(service.lastPurchaseMentionedIn(
                "[圖片解析結果] Microsoft 升級至 Windows 10/11 專業版"))
                .hasValueSatisfying(last -> assertThat(last.record()).isSameAs(windows));
    }

    private static PriceRecord price(int price, String store, LocalDate date) {
        return PriceRecord.record(null, "牛奶", store, price, date, NOW);
    }
}

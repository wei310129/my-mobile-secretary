package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ConsumptionTagCatalog;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemInsightService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceInsightService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ProductExperienceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LifestyleItemIntentServiceTest {

    @Test
    void inventoryOmitsZeroQuantityAndUsesStableCountHeader() {
        ItemService itemService = mock(ItemService.class);
        when(itemService.listInventory(null)).thenReturn(List.of());
        LifestyleItemIntentService service = new LifestyleItemIntentService(
                itemService, mock(ItemInsightService.class), mock(PriceRecordService.class),
                mock(PriceInsightService.class), mock(PlaceAliasService.class),
                mock(PlaceService.class));

        IntentResult result = service.execute(command(IntentCommand.Type.LIST_INVENTORY, null));

        assertThat(result.message()).isEqualTo("📍 共找到 0 個品項：");
        verify(itemService).listInventory(null);
    }

    @Test
    void priceHistoryUsesExistingPriceServiceAndReplyContract() {
        PriceRecordService priceService = mock(PriceRecordService.class);
        when(priceService.list("牛奶")).thenReturn(List.of());
        LifestyleItemIntentService service = new LifestyleItemIntentService(
                mock(ItemService.class),
                mock(ItemInsightService.class),
                priceService,
                mock(PriceInsightService.class),
                mock(PlaceAliasService.class),
                mock(PlaceService.class));

        IntentResult result = service.execute(command(IntentCommand.Type.ASK_PRICE_HISTORY, "牛奶"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PRICE_HISTORY);
        verify(priceService).list("牛奶");
    }

    @Test
    void expenseHistoryReportsDateRangeAuditableTotalAndCategory() {
        PriceRecordService priceService = mock(PriceRecordService.class);
        PriceRecord record = PriceRecord.record(null, "冷氣", "全國電子", 20000, 2,
                40000, ExpenseCategory.ELECTRONICS,
                Set.of("organization:全國電子"), LocalDate.of(2030, 8, 8), Instant.EPOCH);
        when(priceService.search(new PriceRecordService.ExpenseCriteria(
                LocalDate.of(2030, 8, 1), LocalDate.of(2030, 8, 31),
                null, "全國電子", ExpenseCategory.ELECTRONICS)))
                .thenReturn(new PriceRecordService.ExpenseSummary(List.of(record), 40000));
        LifestyleItemIntentService service = new LifestyleItemIntentService(
                mock(ItemService.class), mock(ItemInsightService.class), priceService,
                mock(PriceInsightService.class), mock(PlaceAliasService.class),
                mock(PlaceService.class));
        IntentCommand command = new IntentCommand(IntentCommand.Type.ASK_EXPENSE_HISTORY,
                null, null, "2030-08-01T00:00:00+08:00",
                "2030-08-31T23:59:59+08:00", "全國電子", null, null,
                null, null, null, null, null, IntentOptions.empty().withCategory("ELECTRONICS"));

        IntentResult result = service.execute(command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.EXPENSE_HISTORY_INFO);
        assertThat(result.message()).contains("40000 元", "冷氣", "全國電子", "家電數位");
    }

    @Test
    void addingPaintSurfacesExplicitPrivateCautionWithoutBlockingPurchase() {
        ItemService itemService = mock(ItemService.class);
        when(itemService.addShoppingItems(List.of("油漆"))).thenReturn(List.of(
                Item.create("油漆", Set.of(), Instant.EPOCH)));
        ProductExperienceService experiences = mock(ProductExperienceService.class);
        when(experiences.purchaseCautions(List.of("油漆"))).thenReturn(List.of(
                new ProductExperienceService.PurchaseCaution(
                        "得利 水泥漆 百合白", "曾明確回報不適")));
        LifestyleItemIntentService service = new LifestyleItemIntentService(
                itemService, mock(ItemInsightService.class), mock(PriceRecordService.class),
                mock(PriceInsightService.class), mock(PlaceAliasService.class),
                mock(PlaceService.class));
        service.setProductExperienceService(experiences);
        IntentOptions options = IntentOptions.empty();
        IntentCommand command = new IntentCommand(IntentCommand.Type.ADD_SHOPPING_ITEMS,
                "油漆", null, null, null, null, null, null,
                null, null, null, null, null, options);

        IntentResult result = service.execute(command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.SHOPPING_ITEMS_ADDED);
        assertThat(result.message()).contains("已加入購物清單", "購買前提醒", "不是醫療診斷")
                .doesNotContain("無法購買");
    }

    @Test
    void recentPaymentHistoryDoesNotRequireDateRangeOrIncludeNoticeDrafts() {
        PriceRecordService priceService = mock(PriceRecordService.class);
        PriceRecord electricity = PriceRecord.record(null, "七月電費", "台電", 1200, 1,
                1200, ExpenseCategory.HOUSING, Set.of("activity:payment"),
                LocalDate.of(2030, 8, 8), Instant.EPOCH);
        when(priceService.recentPayments(10)).thenReturn(new PriceRecordService.PaymentHistory(
                List.of(new PriceRecordService.PaymentRecord(
                        electricity, ConsumptionTagCatalog.PaymentKind.UTILITIES)), 1200));
        LifestyleItemIntentService service = new LifestyleItemIntentService(
                mock(ItemService.class), mock(ItemInsightService.class), priceService,
                mock(PriceInsightService.class), mock(PlaceAliasService.class),
                mock(PlaceService.class));

        IntentResult result = service.execute(
                command(IntentCommand.Type.ASK_PAYMENT_HISTORY, null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PAYMENT_HISTORY_INFO);
        assertThat(result.message()).contains("七月電費", "台電", "1200 元", "只列已落帳")
                .contains("通知草稿");
        verify(priceService).recentPayments(10);
    }

    private static IntentCommand command(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}

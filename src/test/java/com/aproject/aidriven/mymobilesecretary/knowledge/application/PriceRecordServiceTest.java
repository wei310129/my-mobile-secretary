package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PriceRecordRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PriceRecordServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-10T04:00:00Z");

    @Test
    void recordPersistsQuantityTotalAndDeterministicTags() {
        PriceRecordRepository repository = mock(PriceRecordRepository.class);
        ItemRepository itemRepository = mock(ItemRepository.class);
        when(itemRepository.findByName("冷氣")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PriceRecordService service = service(repository, itemRepository);

        PriceRecord record = service.record(
                "冷氣", "全國電子", 20000, 2, LocalDate.of(2030, 8, 8));

        assertThat(record.getQuantity()).isEqualTo(2);
        assertThat(record.getTotalPriceTwd()).isEqualTo(40000);
        assertThat(record.getExpenseCategory()).isEqualTo(ExpenseCategory.ELECTRONICS);
        assertThat(record.getSemanticTags()).contains("organization:全國電子");
    }

    @Test
    void searchFiltersMerchantAndCategoryBeforeSummingAuditableTotals() {
        PriceRecordRepository repository = mock(PriceRecordRepository.class);
        LocalDate from = LocalDate.of(2030, 8, 1);
        LocalDate to = LocalDate.of(2030, 8, 31);
        when(repository.findByPurchasedAtBetweenOrderByPurchasedAtDescIdDesc(from, to))
                .thenReturn(List.of(
                        expense("冷氣", "全國電子", 20000, 2,
                                ExpenseCategory.ELECTRONICS, from.plusDays(2)),
                        expense("鮮奶", "全聯", 95, 1,
                                ExpenseCategory.BEVERAGE, from.plusDays(1))));
        PriceRecordService service = service(repository, mock(ItemRepository.class));

        var result = service.search(new PriceRecordService.ExpenseCriteria(
                from, to, null, "全國電子", ExpenseCategory.ELECTRONICS));

        assertThat(result.records()).extracting(PriceRecord::getItemName)
                .containsExactly("冷氣");
        assertThat(result.totalPriceTwd()).isEqualTo(40000);
    }

    @Test
    void keywordSearchMatchesMerchantAndOrganizationTags() {
        PriceRecordRepository repository = mock(PriceRecordRepository.class);
        PriceRecord windows = expense("升級至 Windows 10/11 專業版", "Microsoft", 2999, 1,
                ExpenseCategory.UNKNOWN, LocalDate.of(2024, 10, 1));
        when(repository.findAllByOrderByPurchasedAtDescIdDesc()).thenReturn(List.of(windows));

        assertThat(service(repository, mock(ItemRepository.class)).searchByKeyword("Microsoft"))
                .containsExactly(windows);
    }

    @Test
    void recentPaymentsReturnsOnlyExplicitPaidRecordNames() {
        PriceRecordRepository repository = mock(PriceRecordRepository.class);
        when(repository.findAllByOrderByPurchasedAtDescIdDesc()).thenReturn(List.of(
                expense("七月電費", "台電", 1200, 1,
                        ExpenseCategory.HOUSING, LocalDate.of(2030, 8, 8)),
                expense("鮮奶", "全聯", 95, 1,
                        ExpenseCategory.BEVERAGE, LocalDate.of(2030, 8, 7)),
                expense("停車費", "市府停車場", 60, 1,
                        ExpenseCategory.TRANSPORT, LocalDate.of(2030, 8, 6))));
        PriceRecordService service = service(repository, mock(ItemRepository.class));

        PriceRecordService.PaymentHistory result = service.recentPayments(10);

        assertThat(result.records()).extracting(payment -> payment.record().getItemName())
                .containsExactly("七月電費", "停車費");
        assertThat(result.records()).extracting(PriceRecordService.PaymentRecord::kind)
                .containsExactly(ConsumptionTagCatalog.PaymentKind.UTILITIES,
                        ConsumptionTagCatalog.PaymentKind.PARKING);
        assertThat(result.totalPriceTwd()).isEqualTo(1260);
    }

    private static PriceRecordService service(
            PriceRecordRepository repository, ItemRepository itemRepository) {
        return new PriceRecordService(repository, itemRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), new ConsumptionTagCatalog(),
                mock(SemanticTagGraphService.class));
    }

    private static PriceRecord expense(String name, String merchant, int unitPrice, int quantity,
                                       ExpenseCategory category, LocalDate date) {
        return PriceRecord.record(null, name, merchant, unitPrice, quantity,
                unitPrice * quantity, category,
                Set.of("merchant:" + merchant, "organization:" + merchant), date, NOW);
    }
}

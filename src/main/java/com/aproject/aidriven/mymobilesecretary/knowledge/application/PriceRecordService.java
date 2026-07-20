package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PriceRecordRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 價格歷史 use case:收據品項入庫與查詢。
 * 入庫時嘗試以「名稱完全吻合」連上品項知識庫;對不到留 NULL,價格照存。
 */
@Service
@Transactional
public class PriceRecordService {

    private final PriceRecordRepository priceRecordRepository;
    private final ItemRepository itemRepository;
    private final Clock clock;
    private final ConsumptionTagCatalog tagCatalog;
    private final SemanticTagGraphService tagGraphService;

    public PriceRecordService(PriceRecordRepository priceRecordRepository,
                              ItemRepository itemRepository,
                              Clock clock,
                              ConsumptionTagCatalog tagCatalog,
                              SemanticTagGraphService tagGraphService) {
        this.priceRecordRepository = priceRecordRepository;
        this.itemRepository = itemRepository;
        this.clock = clock;
        this.tagCatalog = tagCatalog;
        this.tagGraphService = tagGraphService;
    }

    /** 存一筆價格紀錄(收據解析或手動);自動連結名稱吻合的品項。 */
    public PriceRecord record(String itemName, String storeName, int priceTwd, LocalDate purchasedAt) {
        return record(itemName, storeName, priceTwd, 1, purchasedAt);
    }

    /** Stores quantity and deterministic tags so totals are never inferred from unit prices. */
    public PriceRecord record(String itemName, String storeName, int priceTwd, int quantity,
                              LocalDate purchasedAt) {
        Instant now = Instant.now(clock);
        Long itemId = itemRepository.findByName(itemName == null ? "" : itemName.strip())
                .map(Item::getId).orElse(null);
        int totalPrice = Math.multiplyExact(priceTwd, quantity);
        ConsumptionTagCatalog.Classification classification =
                tagCatalog.classify(itemName, storeName);
        PriceRecord saved = priceRecordRepository.save(
                PriceRecord.record(itemId, itemName, storeName, priceTwd, quantity, totalPrice,
                        classification.category(), classification.tags(), purchasedAt, now));
        tagGraphService.indexPriceRecord(saved);
        return saved;
    }

    /** 查價格歷史;itemName 空 → 全部(新到舊)。 */
    @Transactional(readOnly = true)
    public List<PriceRecord> list(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return priceRecordRepository.findAllByOrderByPurchasedAtDescIdDesc();
        }
        return priceRecordRepository.findByItemNameContainingOrderByPurchasedAtDescIdDesc(itemName.strip());
    }

    /**
     * Searches an explicit purchase keyword across item, merchant, organization and stored tags.
     * This stays read-only and actor-isolated through the repository tenant boundary.
     */
    @Transactional(readOnly = true)
    public List<PriceRecord> searchByKeyword(String keyword) {
        String needle = normalized(keyword);
        if (needle == null) return List.of();
        return priceRecordRepository.findAllByOrderByPurchasedAtDescIdDesc().stream()
                .filter(record -> contains(record.getItemName(), needle)
                        || contains(record.getStoreName(), needle)
                        || record.getSemanticTags().stream().anyMatch(tag -> contains(tag, needle)))
                .toList();
    }

    /** Actor-isolated, read-only consumption search used by conversational expense queries. */
    @Transactional(readOnly = true)
    public ExpenseSummary search(ExpenseCriteria criteria) {
        if (criteria == null || criteria.from() == null || criteria.to() == null) {
            throw new IllegalArgumentException("expense date range is required");
        }
        if (criteria.to().isBefore(criteria.from())) {
            throw new IllegalArgumentException("expense date range is reversed");
        }
        if (criteria.from().plusYears(5).isBefore(criteria.to())) {
            throw new IllegalArgumentException("expense date range is too large");
        }
        String keyword = normalized(criteria.keyword());
        String merchant = normalized(criteria.merchant());
        List<PriceRecord> records = priceRecordRepository
                .findByPurchasedAtBetweenOrderByPurchasedAtDescIdDesc(criteria.from(), criteria.to())
                .stream()
                .filter(record -> keyword == null || contains(record.getItemName(), keyword)
                        || contains(record.getStoreName(), keyword)
                        || record.getSemanticTags().stream().anyMatch(tag -> contains(tag, keyword)))
                .filter(record -> merchant == null || contains(record.getStoreName(), merchant)
                        || record.getSemanticTags().stream().anyMatch(tag ->
                                (tag.startsWith("merchant:") || tag.startsWith("organization:"))
                                        && contains(tag, merchant)))
                .filter(record -> criteria.category() == null
                        || record.getExpenseCategory() == criteria.category())
                .toList();
        long total = records.stream().mapToLong(PriceRecord::getTotalPriceTwd).sum();
        return new ExpenseSummary(records, total);
    }

    @Transactional(readOnly = true)
    public List<PriceRecord> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return priceRecordRepository.findAllById(ids);
    }

    /** Latest explicitly paid records. Unpaid notice drafts live in another table and cannot enter. */
    @Transactional(readOnly = true)
    public PaymentHistory recentPayments(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("payment history limit must be between 1 and 50");
        }
        List<PaymentRecord> records = priceRecordRepository
                .findAllByOrderByPurchasedAtDescIdDesc().stream()
                .map(record -> tagCatalog.paymentKind(
                                record.getItemName(), record.getStoreName())
                        .map(kind -> new PaymentRecord(record, kind)))
                .flatMap(Optional::stream)
                .limit(limit)
                .toList();
        long total = records.stream()
                .mapToLong(record -> record.record().getTotalPriceTwd()).sum();
        return new PaymentHistory(records, total);
    }

    private static boolean contains(String value, String normalizedNeedle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .contains(normalizedNeedle);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null
                : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** 每家店取最低歷史價,由低到高排列。 */
    @Transactional(readOnly = true)
    public List<StorePrice> compareStores(String itemName) {
        return list(itemName).stream()
                .filter(record -> record.getStoreName() != null && !record.getStoreName().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(PriceRecord::getStoreName))
                .entrySet().stream()
                .map(entry -> entry.getValue().stream()
                        .min(java.util.Comparator.comparingInt(PriceRecord::getPriceTwd))
                        .map(record -> new StorePrice(entry.getKey(), record.getPriceTwd(),
                                record.getPurchasedAt())).orElseThrow())
                .sorted(java.util.Comparator.comparingInt(StorePrice::priceTwd))
                .toList();
    }

    public record StorePrice(String storeName, int priceTwd, LocalDate purchasedAt) {
    }

    public record ExpenseCriteria(LocalDate from, LocalDate to, String keyword,
                                  String merchant, ExpenseCategory category) {
    }

    public record ExpenseSummary(List<PriceRecord> records, long totalPriceTwd) {
    }

    public record PaymentRecord(
            PriceRecord record, ConsumptionTagCatalog.PaymentKind kind) {
    }

    public record PaymentHistory(List<PaymentRecord> records, long totalPriceTwd) {
    }
}

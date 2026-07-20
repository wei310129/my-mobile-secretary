package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 僅根據已記錄單價提供上次購買、價格摘要與常購店家，不推估數量或總支出。 */
@Service
public class PriceInsightService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final PriceRecordService priceRecordService;
    private final Clock clock;

    public PriceInsightService(PriceRecordService priceRecordService, Clock clock) {
        this.priceRecordService = priceRecordService;
        this.clock = clock;
    }

    public Optional<LastPurchase> lastPurchase(String keyword) {
        return priceRecordService.searchByKeyword(keyword).stream().findFirst()
                .map(this::toLastPurchase);
    }

    /** Finds the latest purchase whose exact item or merchant is visible in trusted context. */
    public Optional<LastPurchase> lastPurchaseMentionedIn(String context) {
        String haystack = normalize(context);
        if (haystack.isBlank()) return Optional.empty();
        return priceRecordService.list(null).stream()
                .filter(record -> mentioned(haystack, record.getItemName())
                        || mentioned(haystack, record.getStoreName()))
                .findFirst()
                .map(this::toLastPurchase);
    }

    public Optional<Summary> summary(String itemName) {
        List<PriceRecord> records = priceRecordService.list(itemName);
        if (records.isEmpty()) return Optional.empty();
        PriceRecord lowest = records.stream()
                .min(Comparator.comparingInt(PriceRecord::getPriceTwd)).orElseThrow();
        PriceRecord highest = records.stream()
                .max(Comparator.comparingInt(PriceRecord::getPriceTwd)).orElseThrow();
        int average = (int) Math.round(records.stream()
                .mapToInt(PriceRecord::getPriceTwd).average().orElseThrow());
        Integer latestChange = records.size() < 2 ? null
                : records.get(0).getPriceTwd() - records.get(1).getPriceTwd();
        return Optional.of(new Summary(records.size(), records.getFirst(), lowest,
                highest, average, latestChange));
    }

    public Optional<StoreFrequency> favoriteStore(String itemName) {
        return priceRecordService.list(itemName).stream()
                .filter(record -> record.getStoreName() != null && !record.getStoreName().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(PriceRecord::getStoreName))
                .entrySet().stream()
                .map(entry -> new StoreFrequency(entry.getKey(), entry.getValue().size(),
                        entry.getValue().stream().map(PriceRecord::getPurchasedAt)
                                .max(Comparator.naturalOrder()).orElseThrow()))
                .sorted(Comparator.comparingInt(StoreFrequency::count).reversed()
                        .thenComparing(StoreFrequency::lastPurchasedAt, Comparator.reverseOrder())
                        .thenComparing(StoreFrequency::storeName))
                .findFirst();
    }

    private LastPurchase toLastPurchase(PriceRecord record) {
        return new LastPurchase(record, Math.max(0, ChronoUnit.DAYS.between(
                record.getPurchasedAt(), LocalDate.now(clock.withZone(TAIPEI)))));
    }

    private static boolean mentioned(String context, String value) {
        String candidate = normalize(value);
        return candidate.length() >= 3 && context.contains(candidate);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s「」『』，,。.!！?？:：；;｜|()（）]", "");
    }

    public record LastPurchase(PriceRecord record, long daysAgo) {
    }

    public record Summary(int count, PriceRecord latest, PriceRecord lowest,
                          PriceRecord highest, int averagePriceTwd, Integer latestChangeTwd) {
    }

    public record StoreFrequency(String storeName, int count, LocalDate lastPurchasedAt) {
    }
}

package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PriceRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    public PriceRecordService(PriceRecordRepository priceRecordRepository,
                              ItemRepository itemRepository,
                              Clock clock) {
        this.priceRecordRepository = priceRecordRepository;
        this.itemRepository = itemRepository;
        this.clock = clock;
    }

    /** 存一筆價格紀錄(收據解析或手動);自動連結名稱吻合的品項。 */
    public PriceRecord record(String itemName, String storeName, int priceTwd, LocalDate purchasedAt) {
        Instant now = Instant.now(clock);
        Long itemId = itemRepository.findByName(itemName == null ? "" : itemName.strip())
                .map(Item::getId).orElse(null);
        return priceRecordRepository.save(
                PriceRecord.record(itemId, itemName, storeName, priceTwd, purchasedAt, now));
    }

    /** 查價格歷史;itemName 空 → 全部(新到舊)。 */
    @Transactional(readOnly = true)
    public List<PriceRecord> list(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return priceRecordRepository.findAllByOrderByPurchasedAtDescIdDesc();
        }
        return priceRecordRepository.findByItemNameContainingOrderByPurchasedAtDescIdDesc(itemName.strip());
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
}

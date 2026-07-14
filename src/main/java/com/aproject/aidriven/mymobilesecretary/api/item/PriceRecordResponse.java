package com.aproject.aidriven.mymobilesecretary.api.item;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.time.LocalDate;

/** 價格紀錄的 API 回應。 */
public record PriceRecordResponse(
        Long id,
        Long itemId,
        String itemName,
        String storeName,
        int priceTwd,
        LocalDate purchasedAt
) {

    public static PriceRecordResponse from(PriceRecord record) {
        return new PriceRecordResponse(record.getId(), record.getItemId(), record.getItemName(),
                record.getStoreName(), record.getPriceTwd(), record.getPurchasedAt());
    }
}

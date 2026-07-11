package com.aproject.aidriven.mymobilesecretary.api.item;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import java.time.Instant;
import java.util.Set;

/** 品項知識的 API 回應格式。 */
public record ItemResponse(
        Long id,
        String name,
        Set<Long> placeIds,
        Instant createdAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static ItemResponse from(Item item) {
        return new ItemResponse(item.getId(), item.getName(), item.getPlaceIds(), item.getCreatedAt());
    }
}

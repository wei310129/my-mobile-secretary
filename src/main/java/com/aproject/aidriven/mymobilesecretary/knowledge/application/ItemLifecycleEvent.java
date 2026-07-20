package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import java.time.Instant;

/** User-relevant shopping, inventory and item-knowledge lifecycle change. */
public record ItemLifecycleEvent(
        Long itemId, String itemName, Action action, Integer quantity, Instant occurredAt) {

    public enum Action {
        CREATED,
        SHOPPING_ADDED,
        SHOPPING_REMOVED,
        PURCHASED,
        INVENTORY_ADJUSTED,
        INVENTORY_SET,
        RESTOCK_REQUESTED,
        SHOPPING_CLEARED,
        PLACE_BOUND
    }
}

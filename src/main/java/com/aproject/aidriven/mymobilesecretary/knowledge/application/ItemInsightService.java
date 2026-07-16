package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 只使用可確定的品項事實；庫存 0 可能是未盤點，因此不在此推斷缺貨。 */
@Service
public class ItemInsightService {
    private final ItemService itemService;

    public ItemInsightService(ItemService itemService) {
        this.itemService = itemService;
    }

    public Optional<InventoryExtremes> inventoryExtremes() {
        List<Item> known = itemService.listInventory(null);
        if (known.isEmpty()) return Optional.empty();
        Comparator<Item> order = Comparator.comparingInt(Item::getInventoryQuantity)
                .thenComparing(Item::getName);
        return Optional.of(new InventoryExtremes(
                known.stream().min(order).orElseThrow(),
                known.stream().max(order).orElseThrow()));
    }

    public List<Item> shoppingItemsWithStock() {
        return itemService.listShoppingItems().stream()
                .filter(item -> item.getInventoryQuantity() > 0)
                .toList();
    }

    public List<Item> itemsWithoutPlace() {
        return itemService.listItems().stream()
                .filter(item -> item.getPlaceIds().isEmpty())
                .sorted(Comparator.comparing(Item::getName))
                .toList();
    }

    public Summary summary() {
        List<Item> all = itemService.listItems();
        return new Summary(all.size(),
                (int) all.stream().filter(item -> item.getInventoryQuantity() > 0).count(),
                (int) all.stream().filter(Item::isShoppingNeeded).count(),
                (int) all.stream().filter(item -> !item.getPlaceIds().isEmpty()).count());
    }

    public record InventoryExtremes(Item lowest, Item highest) {
    }

    public record Summary(int knownItems, int inventoriedItems,
                          int shoppingItems, int itemsWithPlace) {
    }
}

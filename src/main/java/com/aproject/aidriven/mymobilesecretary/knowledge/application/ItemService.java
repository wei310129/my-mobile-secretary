package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物品知識 use case:登錄「這個品項在哪裡買得到」。
 */
@Service
@Transactional
public class ItemService {

    private final ItemRepository itemRepository;
    private final PlaceService placeService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ItemService(ItemRepository itemRepository, PlaceService placeService,
                       ApplicationEventPublisher eventPublisher, Clock clock) {
        this.itemRepository = itemRepository;
        this.placeService = placeService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 登錄品項知識。
     *
     * 規則:品項名唯一(重複 → 422);每個地點必須存在(不存在 → 404),
     * 避免知識庫指向幽靈地點。
     */
    public Item createItem(String name, Set<Long> placeIds) {
        if (itemRepository.existsByName(name)) {
            throw new BusinessException("DUPLICATE_ITEM", "Item already exists: %s".formatted(name));
        }
        // 驗證所有地點都存在
        for (Long placeId : new LinkedHashSet<>(placeIds)) {
            placeService.getPlace(placeId);
        }
        Instant now = Instant.now(clock);
        Item saved = itemRepository.save(Item.create(name, placeIds, now));
        publish(saved, ItemLifecycleEvent.Action.CREATED, null, now);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Item> listItems() {
        return itemRepository.findAll();
    }

    /** 找出文字中提到的所有已知品項(自動綁定的比對入口)。 */
    @Transactional(readOnly = true)
    public List<Item> findItemsMentionedIn(String text) {
        return itemRepository.findAll().stream()
                .filter(item -> item.isMentionedIn(text))
                .toList();
    }

    /** 加入購物清單。名稱比對不分大小寫且天然去重。 */
    public List<Item> addShoppingItems(List<String> names) {
        Instant now = Instant.now(clock);
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .distinct()
                .map(name -> {
                    Item item = itemRepository.findByNameIgnoreCase(name)
                            .orElseGet(() -> itemRepository.save(Item.create(name, Set.of(), now)));
                    item.markShoppingNeeded(now);
                    publish(item, ItemLifecycleEvent.Action.SHOPPING_ADDED, null, now);
                    return item;
                })
                .toList();
    }

    /** 從購物清單移除;找不到時回 empty,讓對話層友善回覆。 */
    public java.util.Optional<Item> removeShoppingItem(String name) {
        return itemRepository.findByNameIgnoreCase(name.strip())
                .map(item -> {
                    item.removeFromShoppingList(Instant.now(clock));
                    publish(item, ItemLifecycleEvent.Action.SHOPPING_REMOVED,
                            null, item.getUpdatedAt());
                    return item;
                });
    }

    @Transactional(readOnly = true)
    public List<Item> listShoppingItems() {
        return itemRepository.findByShoppingNeededTrueOrderByNameAsc();
    }

    /** 指定品項已買到;不存在或本來不在清單的名稱略過。 */
    public List<Item> markShoppingPurchased(List<String> names, Integer purchasedQuantity) {
        Instant now = Instant.now(clock);
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(java.util.stream.Collectors.toMap(
                        name -> name.toLowerCase(java.util.Locale.ROOT),
                        name -> name,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .map(itemRepository::findByNameIgnoreCase)
                .flatMap(java.util.Optional::stream)
                .filter(Item::isShoppingNeeded)
                .peek(item -> {
                    item.removeFromShoppingList(now);
                    if (purchasedQuantity != null && purchasedQuantity > 0) {
                        item.adjustInventoryQuantity(purchasedQuantity, now);
                    }
                    publish(item, ItemLifecycleEvent.Action.PURCHASED,
                            purchasedQuantity, now);
                })
                .toList();
    }

    /** 依相對數量調整庫存;品項不存在時從 0 建立後再調整。 */
    public Item adjustInventory(String name, int delta) {
        Instant now = Instant.now(clock);
        java.util.Optional<Item> existing = itemRepository.findByNameIgnoreCase(name.strip());
        if (delta < 0 && (existing.isEmpty() || existing.get().getInventoryQuantity() == 0)) {
            throw new BusinessException("UNKNOWN_INVENTORY",
                    "「%s」目前是 0 或還沒盤點,不能再直接扣庫存;請告訴我正確數量。".formatted(name.strip()));
        }
        Item item = existing.orElseGet(() -> itemRepository.save(Item.create(name.strip(), Set.of(), now)));
        item.adjustInventoryQuantity(delta, now);
        publish(item, ItemLifecycleEvent.Action.INVENTORY_ADJUSTED,
                item.getInventoryQuantity(), now);
        return item;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Item> findItem(String name) {
        return name == null || name.isBlank() ? java.util.Optional.empty()
                : itemRepository.findByNameIgnoreCase(name.strip());
    }

    @Transactional(readOnly = true)
    public List<Item> listInventory(Integer maximumQuantity) {
        return itemRepository.findAll().stream()
                // 0 目前也可能代表「尚未盤點」,不可誤報成已用完。
                .filter(item -> item.getInventoryQuantity() > 0)
                .filter(item -> maximumQuantity == null
                        || item.getInventoryQuantity() <= Math.max(maximumQuantity, 0))
                .sorted(java.util.Comparator.comparingInt(Item::getInventoryQuantity)
                        .thenComparing(Item::getName))
                .toList();
    }

    /** 把低庫存品項加入購物清單,門檻由使用者明確指定或預設 0。 */
    public List<Item> restockLowInventory(int maximumQuantity) {
        Instant now = Instant.now(clock);
        List<Item> items = listInventory(Math.max(maximumQuantity, 0));
        items.forEach(item -> {
            item.markShoppingNeeded(now);
            publish(item, ItemLifecycleEvent.Action.RESTOCK_REQUESTED,
                    item.getInventoryQuantity(), now);
        });
        return items;
    }

    @Transactional(readOnly = true)
    public List<Item> listKnownItemsAt(Long placeId) {
        return itemRepository.findAll().stream()
                .filter(item -> item.getPlaceIds().contains(placeId))
                .sorted(java.util.Comparator.comparing(Item::getName))
                .toList();
    }

    /** 清空目前購物清單,保留品項與價格／店家知識。 */
    public List<Item> clearShoppingList() {
        Instant now = Instant.now(clock);
        List<Item> items = itemRepository.findByShoppingNeededTrueOrderByNameAsc();
        items.forEach(item -> {
            item.removeFromShoppingList(now);
            publish(item, ItemLifecycleEvent.Action.SHOPPING_CLEARED, null, now);
        });
        return items;
    }

    @Transactional(readOnly = true)
    public List<Item> listShoppingItemsAt(Long placeId) {
        return itemRepository.findByShoppingNeededTrueOrderByNameAsc().stream()
                .filter(item -> item.getPlaceIds().contains(placeId))
                .toList();
    }

    /** 更新家中庫存;品項不存在時一併建立。 */
    public Item setInventory(String name, int quantity) {
        Instant now = Instant.now(clock);
        Item item = itemRepository.findByNameIgnoreCase(name.strip())
                .orElseGet(() -> itemRepository.save(Item.create(name.strip(), Set.of(), now)));
        item.setInventoryQuantity(quantity, now);
        if (quantity > 0) {
            item.removeFromShoppingList(now);
        }
        publish(item, ItemLifecycleEvent.Action.INVENTORY_SET, quantity, now);
        return item;
    }

    /** 記住品項可在哪裡買;兩端都驗證且關聯去重。 */
    public Item bindItemToPlace(String name, Long placeId) {
        placeService.getPlace(placeId);
        Instant now = Instant.now(clock);
        Item item = itemRepository.findByNameIgnoreCase(name.strip())
                .orElseGet(() -> itemRepository.save(Item.create(name.strip(), Set.of(), now)));
        item.addPlace(placeId, now);
        publish(item, ItemLifecycleEvent.Action.PLACE_BOUND, null, now);
        return item;
    }

    private void publish(Item item, ItemLifecycleEvent.Action action,
                         Integer quantity, Instant occurredAt) {
        eventPublisher.publishEvent(new ItemLifecycleEvent(
                item.getId(), item.getName(), action, quantity, occurredAt));
    }
}

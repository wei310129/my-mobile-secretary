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
    private final Clock clock;

    public ItemService(ItemRepository itemRepository, PlaceService placeService, Clock clock) {
        this.itemRepository = itemRepository;
        this.placeService = placeService;
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
        return itemRepository.save(Item.create(name, placeIds, Instant.now(clock)));
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
                    return item;
                })
                .toList();
    }

    /** 從購物清單移除;找不到時回 empty,讓對話層友善回覆。 */
    public java.util.Optional<Item> removeShoppingItem(String name) {
        return itemRepository.findByNameIgnoreCase(name.strip())
                .map(item -> {
                    item.removeFromShoppingList(Instant.now(clock));
                    return item;
                });
    }

    @Transactional(readOnly = true)
    public List<Item> listShoppingItems() {
        return itemRepository.findByShoppingNeededTrueOrderByNameAsc();
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
        return item;
    }

    /** 記住品項可在哪裡買;兩端都驗證且關聯去重。 */
    public Item bindItemToPlace(String name, Long placeId) {
        placeService.getPlace(placeId);
        Instant now = Instant.now(clock);
        Item item = itemRepository.findByNameIgnoreCase(name.strip())
                .orElseGet(() -> itemRepository.save(Item.create(name.strip(), Set.of(), now)));
        item.addPlace(placeId, now);
        return item;
    }
}

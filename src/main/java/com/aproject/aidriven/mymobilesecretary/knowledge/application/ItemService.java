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
}

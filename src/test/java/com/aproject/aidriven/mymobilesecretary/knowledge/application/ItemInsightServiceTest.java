package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItemInsightServiceTest {
    private static final Instant NOW = Instant.parse("2030-08-10T04:00:00Z");

    @Mock
    private ItemService itemService;

    @Test
    void summarizesOnlyCertainItemFacts() {
        Item milk = item("牛奶", 2, true, Set.of(1L));
        Item tissue = item("衛生紙", 5, false, Set.of());
        when(itemService.listItems()).thenReturn(List.of(milk, tissue));
        when(itemService.listInventory(null)).thenReturn(List.of(milk, tissue));
        when(itemService.listShoppingItems()).thenReturn(List.of(milk));
        ItemInsightService service = new ItemInsightService(itemService);

        assertThat(service.summary()).extracting(ItemInsightService.Summary::knownItems,
                        ItemInsightService.Summary::inventoriedItems,
                        ItemInsightService.Summary::shoppingItems,
                        ItemInsightService.Summary::itemsWithPlace)
                .containsExactly(2, 2, 1, 1);
        assertThat(service.inventoryExtremes()).hasValueSatisfying(extremes -> {
            assertThat(extremes.lowest()).isSameAs(milk);
            assertThat(extremes.highest()).isSameAs(tissue);
        });
        assertThat(service.shoppingItemsWithStock()).containsExactly(milk);
        assertThat(service.itemsWithoutPlace()).containsExactly(tissue);
    }

    private static Item item(String name, int quantity, boolean shopping, Set<Long> places) {
        Item item = Item.create(name, places, NOW);
        item.setInventoryQuantity(quantity, NOW);
        if (shopping) item.markShoppingNeeded(NOW);
        return item;
    }
}

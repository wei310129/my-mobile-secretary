package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ItemLifestyleServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T00:00:00Z");

    @Mock
    private ItemRepository repository;
    @Mock
    private PlaceService placeService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ItemService service;

    @BeforeEach
    void setUp() {
        service = new ItemService(repository, placeService,
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void lowInventoryExcludesUncountedZeroAndSortsAscending() {
        Item unknown = item("未知", 0);
        Item one = item("只剩一個", 1);
        Item three = item("還有三個", 3);
        when(repository.findAll()).thenReturn(List.of(three, unknown, one));

        assertThat(service.listInventory(3)).extracting(Item::getName)
                .containsExactly("只剩一個", "還有三個");
    }

    @Test
    void restockOnlyMarksCountedItemsAtOrBelowThreshold() {
        Item unknown = item("未知", 0);
        Item one = item("只剩一個", 1);
        Item three = item("還有三個", 3);
        when(repository.findAll()).thenReturn(List.of(unknown, one, three));

        List<Item> restocked = service.restockLowInventory(1);

        assertThat(restocked).containsExactly(one);
        assertThat(one.isShoppingNeeded()).isTrue();
        assertThat(unknown.isShoppingNeeded()).isFalse();
        assertThat(three.isShoppingNeeded()).isFalse();
    }

    @Test
    void purchasedQuantityClosesShoppingAndAddsInventory() {
        Item milk = item("牛奶", 1);
        milk.markShoppingNeeded(NOW);
        when(repository.findByNameIgnoreCase("牛奶")).thenReturn(Optional.of(milk));

        List<Item> purchased = service.markShoppingPurchased(List.of("牛奶"), 2);

        assertThat(purchased).containsExactly(milk);
        assertThat(milk.isShoppingNeeded()).isFalse();
        assertThat(milk.getInventoryQuantity()).isEqualTo(3);
    }

    @Test
    void consumingUnknownInventoryAsksForCorrectionInsteadOfInventingZero() {
        when(repository.findByNameIgnoreCase("牛奶")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustInventory("牛奶", -1))
                .hasMessageContaining("還沒盤點");
    }

    private static Item item(String name, int quantity) {
        Item item = Item.create(name, Set.of(), NOW);
        item.setInventoryQuantity(quantity, NOW);
        return item;
    }
}

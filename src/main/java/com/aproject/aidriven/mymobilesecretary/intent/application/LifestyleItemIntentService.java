package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemInsightService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceInsightService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Deterministic shopping, inventory, item-place and price-history intent execution. */
@Service
@RequiredArgsConstructor
public class LifestyleItemIntentService {

    private final ItemService itemService;
    private final ItemInsightService itemInsightService;
    private final PriceRecordService priceService;
    private final PriceInsightService priceInsightService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;

    public IntentResult execute(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        return switch (command.type()) {
            case ADD_SHOPPING_ITEMS -> addShopping(command, options);
            case REMOVE_SHOPPING_ITEM -> removeShopping(command, options);
            case LIST_SHOPPING_ITEMS -> listShopping();
            case SET_INVENTORY -> setInventory(command, options);
            case ASK_PRICE_COMPARISON -> comparePrices(command);
            case MARK_SHOPPING_PURCHASED -> markShoppingPurchased(command, options);
            case CLEAR_SHOPPING_LIST -> clearShopping();
            case LIST_SHOPPING_BY_PLACE -> listShoppingAt(command);
            case ADJUST_INVENTORY -> adjustInventory(command, options);
            case LIST_INVENTORY -> listInventory(options);
            case ASK_ITEM_PLACES -> askItemPlaces(command);
            case BIND_ITEM_PLACE -> bindItemPlace(command);
            case LIST_ITEMS_BY_PLACE -> listItemsAt(command);
            case GROUP_SHOPPING_BY_PLACE -> groupShoppingByPlace();
            case RESTOCK_LOW_INVENTORY -> restockLowInventory(options);
            case ASK_LAST_PURCHASE -> lastPurchase(command);
            case ASK_PRICE_SUMMARY -> priceSummary(command);
            case ASK_FREQUENT_STORE -> frequentStore(command);
            case ASK_INVENTORY_EXTREMES -> inventoryExtremes(options);
            case CHECK_SHOPPING_INVENTORY -> checkShoppingInventory();
            case LIST_UNPLACED_ITEMS -> listUnplacedItems();
            case ASK_ITEM_KNOWLEDGE_SUMMARY -> itemKnowledgeSummary();
            default -> throw new IllegalArgumentException(
                    "not an item lifestyle command: " + command.type());
        };
    }

    private IntentResult addShopping(IntentCommand command, IntentOptions options) {
        List<String> names = itemNames(command, options);
        List<Item> items = itemService.addShoppingItems(names);
        if (hasText(command.placeName())) {
            Place place = resolvePlace(command.placeName()).orElseGet(() ->
                    placeService.createPlace(command.placeName(), null, null, null, null));
            items.forEach(item -> itemService.bindItemToPlace(item.getName(), place.getId()));
        }
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_ADDED,
                "已加入購物清單:\n%s\n\n重複品項不會再新增一份。".formatted(
                        items.stream().map(Item::getName).collect(Collectors.joining("\n"))));
    }

    private IntentResult removeShopping(IntentCommand command, IntentOptions options) {
        String name = itemNames(command, options).getFirst();
        boolean removed = itemService.removeShoppingItem(name).isPresent();
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEM_REMOVED,
                removed ? "已從購物清單移除「%s」。".formatted(name)
                        : "購物清單裡沒有「%s」。".formatted(name));
    }

    private IntentResult listShopping() {
        List<Item> items = itemService.listShoppingItems();
        String message = items.isEmpty() ? "購物清單目前是空的。" : "還要買:\n"
                + items.stream().map(Item::getName).collect(Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SHOPPING_LISTED, message);
    }

    private IntentResult setInventory(IntentCommand command, IntentOptions options) {
        String name = itemNames(command, options).getFirst();
        Item item = itemService.setInventory(name,
                options.quantity() == null ? 0 : options.quantity());
        return IntentResult.message(IntentResult.Action.INVENTORY_UPDATED,
                "已更新「%s」庫存為 %d。".formatted(
                        item.getName(), item.getInventoryQuantity()));
    }

    private IntentResult markShoppingPurchased(IntentCommand command, IntentOptions options) {
        List<Item> purchased = itemService.markShoppingPurchased(
                itemNames(command, options), options.quantity());
        if (purchased.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_PURCHASED,
                    "這些品項目前不在購物清單裡。 ");
        }
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_PURCHASED,
                "已標記買到:\n%s".formatted(purchased.stream().map(Item::getName)
                        .collect(Collectors.joining("\n"))));
    }

    private IntentResult clearShopping() {
        List<Item> cleared = itemService.clearShoppingList();
        return IntentResult.message(IntentResult.Action.SHOPPING_LIST_CLEARED,
                cleared.isEmpty() ? "購物清單本來就是空的。"
                        : "已清空購物清單,共 %d 項。".formatted(cleared.size()));
    }

    private IntentResult adjustInventory(IntentCommand command, IntentOptions options) {
        require(command.title(), "title");
        if (options.quantity() == null || options.quantity() == 0) {
            throw new IllegalArgumentException("missing inventory delta");
        }
        Item item = itemService.adjustInventory(command.title(), options.quantity());
        return IntentResult.message(IntentResult.Action.INVENTORY_ADJUSTED,
                "已把「%s」庫存%s %d,目前是 %d。".formatted(item.getName(),
                        options.quantity() > 0 ? "增加" : "減少",
                        Math.abs(options.quantity()), item.getInventoryQuantity()));
    }

    private IntentResult listInventory(IntentOptions options) {
        Integer maximum = "LOW".equalsIgnoreCase(options.filter())
                ? (options.quantity() == null ? 1 : options.quantity()) : null;
        List<Item> items = itemService.listInventory(maximum);
        if ("AT_LEAST".equalsIgnoreCase(options.filter()) && options.quantity() != null) {
            items = itemService.listInventory(null).stream()
                    .filter(item -> item.getInventoryQuantity() >= options.quantity()).toList();
        } else if ("EXACT".equalsIgnoreCase(options.filter()) && options.quantity() != null) {
            items = itemService.listInventory(null).stream()
                    .filter(item -> item.getInventoryQuantity() == options.quantity()).toList();
        }
        String message = items.isEmpty()
                ? (maximum == null ? "目前沒有大於 0 的庫存紀錄。" : "沒有已知的低庫存品項。")
                : "庫存清單:\n" + items.stream()
                        .map(item -> "%s｜%d".formatted(
                                item.getName(), item.getInventoryQuantity()))
                        .collect(Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.INVENTORY_LISTED, message);
    }

    private IntentResult inventoryExtremes(IntentOptions options) {
        return itemInsightService.inventoryExtremes().map(extremes -> {
            Item selected = "LOW".equalsIgnoreCase(options.filter())
                    ? extremes.lowest() : extremes.highest();
            String message = "RANGE".equalsIgnoreCase(options.filter())
                    ? "已知正庫存從「%s」%d 到「%s」%d；不同品項單位可能不同。".formatted(
                            extremes.lowest().getName(), extremes.lowest().getInventoryQuantity(),
                            extremes.highest().getName(), extremes.highest().getInventoryQuantity())
                    : "已知正庫存%s的是「%s」，數量 %d；不同品項單位可能不同。".formatted(
                            "LOW".equalsIgnoreCase(options.filter()) ? "最少" : "最多",
                            selected.getName(), selected.getInventoryQuantity());
            return IntentResult.message(IntentResult.Action.INVENTORY_EXTREMES_INFO, message);
        }).orElseGet(() -> IntentResult.message(IntentResult.Action.INVENTORY_EXTREMES_INFO,
                "目前沒有大於 0 的庫存紀錄；數量 0 可能是尚未盤點，不能當成缺貨。"));
    }

    private IntentResult checkShoppingInventory() {
        List<Item> items = itemInsightService.shoppingItemsWithStock();
        String message = items.isEmpty() ? "購物清單中沒有同時具有正庫存紀錄的品項。"
                : "購物清單中這些品項仍有已知庫存:\n%s".formatted(items.stream()
                        .map(item -> "%s %d".formatted(
                                item.getName(), item.getInventoryQuantity()))
                        .collect(Collectors.joining("\n")));
        return IntentResult.message(IntentResult.Action.SHOPPING_INVENTORY_CHECKED, message);
    }

    private IntentResult listUnplacedItems() {
        List<Item> items = itemInsightService.itemsWithoutPlace();
        return IntentResult.message(IntentResult.Action.UNPLACED_ITEMS_LISTED,
                items.isEmpty() ? "所有已知品項都至少有一個購買地點。"
                        : "還沒記錄購買地點:\n%s".formatted(items.stream()
                                .map(Item::getName).collect(Collectors.joining("\n"))));
    }

    private IntentResult itemKnowledgeSummary() {
        var summary = itemInsightService.summary();
        return IntentResult.message(IntentResult.Action.ITEM_KNOWLEDGE_SUMMARY,
                "共記得 %d 個品項：%d 個有正庫存、%d 個在購物清單、%d 個有購買地點。"
                        .formatted(summary.knownItems(), summary.inventoriedItems(),
                                summary.shoppingItems(), summary.itemsWithPlace()));
    }

    private IntentResult askItemPlaces(IntentCommand command) {
        require(command.title(), "title");
        Optional<Item> found = itemService.findItem(command.title());
        if (found.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "我還沒有「%s」的品項紀錄。".formatted(command.title()));
        }
        Item item = found.get();
        List<String> places = item.getPlaceIds().stream().map(placeService::getPlace)
                .map(Place::getName).sorted().toList();
        return IntentResult.message(IntentResult.Action.ITEM_PLACES_INFO,
                places.isEmpty() ? "還不知道「%s」可以在哪裡買。".formatted(item.getName())
                        : "「%s」可以在:\n%s".formatted(
                                item.getName(), String.join("\n", places)));
    }

    private IntentResult bindItemPlace(IntentCommand command) {
        require(command.title(), "title");
        require(command.placeName(), "placeName");
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        Item item = itemService.bindItemToPlace(command.title(), place.getId());
        return IntentResult.message(IntentResult.Action.ITEM_PLACE_BOUND,
                "記住了,「%s」可以在「%s」買。".formatted(
                        item.getName(), place.getName()));
    }

    private IntentResult listItemsAt(IntentCommand command) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        List<Item> items = itemService.listKnownItemsAt(place.getId());
        String message = items.isEmpty()
                ? "目前沒有記錄「%s」可買的品項。".formatted(place.getName())
                : "記得「%s」可買:\n%s".formatted(place.getName(), items.stream()
                        .map(Item::getName).collect(Collectors.joining("\n")));
        return IntentResult.message(IntentResult.Action.ITEMS_BY_PLACE_LISTED, message);
    }

    private IntentResult groupShoppingByPlace() {
        List<Item> shopping = itemService.listShoppingItems();
        if (shopping.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SHOPPING_GROUPED_BY_PLACE,
                    "購物清單目前是空的。");
        }
        Map<Long, String> placeNames = placeService.listPlaces().stream()
                .collect(Collectors.toMap(Place::getId, Place::getName));
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Item item : shopping) {
            if (item.getPlaceIds().isEmpty()) {
                groups.computeIfAbsent("未指定店家", ignored -> new ArrayList<>())
                        .add(item.getName());
            } else {
                for (Long placeId : item.getPlaceIds()) {
                    groups.computeIfAbsent(placeNames.getOrDefault(placeId, "未知地點"),
                            ignored -> new ArrayList<>()).add(item.getName());
                }
            }
        }
        String message = groups.entrySet().stream()
                .map(entry -> "%s（%d）\n%s".formatted(entry.getKey(),
                        entry.getValue().size(), String.join("\n", entry.getValue())))
                .collect(Collectors.joining("\n\n"));
        return IntentResult.message(IntentResult.Action.SHOPPING_GROUPED_BY_PLACE, message);
    }

    private IntentResult restockLowInventory(IntentOptions options) {
        int maximum = options.quantity() == null ? 1 : Math.max(options.quantity(), 1);
        List<Item> items = itemService.restockLowInventory(maximum);
        return IntentResult.message(IntentResult.Action.LOW_INVENTORY_RESTOCKED,
                items.isEmpty() ? "沒有已盤點且低於門檻的庫存。"
                        : "已把低庫存加入購物清單:\n%s".formatted(items.stream()
                                .map(Item::getName).collect(Collectors.joining("\n"))));
    }

    private IntentResult listShoppingAt(IntentCommand command) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        List<Item> items = itemService.listShoppingItemsAt(place.getId());
        String message = items.isEmpty()
                ? "目前沒有綁在「%s」的待買品項。".formatted(place.getName())
                : "到「%s」要買:\n%s".formatted(place.getName(), items.stream()
                        .map(Item::getName).collect(Collectors.joining("\n")));
        return IntentResult.message(IntentResult.Action.SHOPPING_BY_PLACE_LISTED, message);
    }

    private IntentResult comparePrices(IntentCommand command) {
        require(command.title(), "title");
        var prices = priceService.compareStores(command.title());
        if (prices.isEmpty()) {
            return IntentResult.message(IntentResult.Action.PRICE_COMPARISON,
                    "目前沒有「%s」可比較的店家價格。".formatted(command.title()));
        }
        String lines = prices.stream().map(price -> "%s｜%d 元｜%s".formatted(
                        price.storeName(), price.priceTwd(), price.purchasedAt()))
                .collect(Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.PRICE_COMPARISON,
                "「%s」歷史最低價比較:\n%s".formatted(command.title(), lines));
    }

    private IntentResult lastPurchase(IntentCommand command) {
        require(command.title(), "title");
        return priceInsightService.lastPurchase(command.title()).map(last -> {
            var record = last.record();
            String store = hasText(record.getStoreName())
                    ? "，在%s".formatted(record.getStoreName()) : "，店家未記錄";
            String ago = last.daysAgo() == 0 ? "今天" : last.daysAgo() + " 天前";
            return IntentResult.message(IntentResult.Action.LAST_PURCHASE_INFO,
                    "上次買「%s」是 %s（%s）%s，單價 %d 元。".formatted(
                            command.title(), record.getPurchasedAt(), ago, store,
                            record.getPriceTwd()));
        }).orElseGet(() -> IntentResult.message(IntentResult.Action.LAST_PURCHASE_INFO,
                "目前沒有「%s」的購買紀錄。".formatted(command.title())));
    }

    private IntentResult priceSummary(IntentCommand command) {
        require(command.title(), "title");
        return priceInsightService.summary(command.title()).map(summary -> {
            Integer change = summary.latestChangeTwd();
            String trend = change == null ? "只有一筆，還不能比較漲跌"
                    : change > 0 ? "比前一次貴 %d 元".formatted(change)
                    : change < 0 ? "比前一次便宜 %d 元".formatted(Math.abs(change))
                    : "和前一次相同";
            return IntentResult.message(IntentResult.Action.PRICE_SUMMARY_INFO,
                    "「%s」共有 %d 筆單價紀錄：平均 %d 元，最低 %d 元，最高 %d 元；最近 %d 元，%s。"
                            .formatted(command.title(), summary.count(), summary.averagePriceTwd(),
                                    summary.lowest().getPriceTwd(),
                                    summary.highest().getPriceTwd(),
                                    summary.latest().getPriceTwd(), trend));
        }).orElseGet(() -> IntentResult.message(IntentResult.Action.PRICE_SUMMARY_INFO,
                "目前沒有「%s」的價格紀錄。".formatted(command.title())));
    }

    private IntentResult frequentStore(IntentCommand command) {
        require(command.title(), "title");
        return priceInsightService.favoriteStore(command.title())
                .map(store -> IntentResult.message(IntentResult.Action.FREQUENT_STORE_INFO,
                        "「%s」最常記錄在%s購買，共 %d 次；最近一次是 %s。".formatted(
                                command.title(), store.storeName(), store.count(),
                                store.lastPurchasedAt())))
                .orElseGet(() -> IntentResult.message(IntentResult.Action.FREQUENT_STORE_INFO,
                        "目前沒有「%s」含店家的購買紀錄。".formatted(command.title())));
    }

    private Optional<Place> resolvePlace(String name) {
        return placeAliasService.resolve(name);
    }

    private static List<String> itemNames(IntentCommand command, IntentOptions options) {
        List<String> names = options.itemNames();
        if (names == null || names.isEmpty()) {
            require(command.title(), "itemNames");
            names = List.of(command.title());
        }
        return names;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " missing");
        }
    }
}

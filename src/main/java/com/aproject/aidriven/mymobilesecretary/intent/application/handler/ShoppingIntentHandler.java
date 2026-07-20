package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.LifestyleItemIntentService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Handles deterministic shopping, inventory, item-place and price knowledge commands. */
@Component
@RequiredArgsConstructor
public final class ShoppingIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.ADD_SHOPPING_ITEMS,
            IntentCommand.Type.ADJUST_INVENTORY,
            IntentCommand.Type.ASK_FREQUENT_STORE,
            IntentCommand.Type.ASK_INVENTORY_EXTREMES,
            IntentCommand.Type.ASK_ITEM_KNOWLEDGE_SUMMARY,
            IntentCommand.Type.ASK_ITEM_PLACES,
            IntentCommand.Type.ASK_LAST_PURCHASE,
            IntentCommand.Type.ASK_PRICE_COMPARISON,
            IntentCommand.Type.ASK_PRICE_HISTORY,
            IntentCommand.Type.ASK_PRICE_SUMMARY,
            IntentCommand.Type.ASK_EXPENSE_HISTORY,
            IntentCommand.Type.ASK_PAYMENT_HISTORY,
            IntentCommand.Type.BIND_ITEM_PLACE,
            IntentCommand.Type.CHECK_SHOPPING_INVENTORY,
            IntentCommand.Type.CLEAR_SHOPPING_LIST,
            IntentCommand.Type.GROUP_SHOPPING_BY_PLACE,
            IntentCommand.Type.LIST_INVENTORY,
            IntentCommand.Type.LIST_ITEMS_BY_PLACE,
            IntentCommand.Type.LIST_SHOPPING_BY_PLACE,
            IntentCommand.Type.LIST_SHOPPING_ITEMS,
            IntentCommand.Type.LIST_UNPLACED_ITEMS,
            IntentCommand.Type.MARK_SHOPPING_PURCHASED,
            IntentCommand.Type.REMOVE_SHOPPING_ITEM,
            IntentCommand.Type.RESTOCK_LOW_INVENTORY,
            IntentCommand.Type.SET_INVENTORY);

    private final LifestyleItemIntentService itemIntentService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        if (!SUPPORTED_TYPES.contains(command.type())) {
            throw new IllegalArgumentException("unsupported shopping intent type " + command.type());
        }
        try {
            return itemIntentService.execute(command);
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }
}

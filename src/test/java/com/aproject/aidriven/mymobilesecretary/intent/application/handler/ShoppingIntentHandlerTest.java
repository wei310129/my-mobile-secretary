package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.LifestyleItemIntentService;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ShoppingIntentHandlerTest {

    private static final Set<IntentCommand.Type> SHOPPING_TYPES = Set.of(
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

    private LifestyleItemIntentService itemIntentService;
    private ShoppingIntentHandler handler;

    @BeforeEach
    void setUp() {
        itemIntentService = mock(LifestyleItemIntentService.class);
        handler = new ShoppingIntentHandler(itemIntentService);
    }

    @Test
    void registersEveryShoppingInventoryAndKnowledgeType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(SHOPPING_TYPES);
    }

    @ParameterizedTest
    @MethodSource("shoppingTypes")
    void delegatesEachCommandToExistingDeterministicItemService(IntentCommand.Type type) {
        IntentCommand command = command(type);
        IntentResult expected = IntentResult.message(IntentResult.Action.ITEM_KNOWLEDGE_SUMMARY, type.name());
        when(itemIntentService.execute(command)).thenReturn(expected);

        IntentResult result = handler.handle("command", command);

        assertThat(result).isSameAs(expected);
        verify(itemIntentService).execute(command);
    }

    @Test
    void keepsLegacyLifestyleClarificationMapping() {
        IntentCommand command = command(IntentCommand.Type.LIST_ITEMS_BY_PLACE);
        when(itemIntentService.execute(command))
                .thenThrow(new IllegalArgumentException("unknown destination place"));

        IntentResult result = handler.handle("query", command);

        assertThat(result).isEqualTo(IntentResult.clarificationNeeded(
                "我找不到目的地,請說完整地點名稱或先建立地點。"));
    }

    private static Stream<IntentCommand.Type> shoppingTypes() {
        return SHOPPING_TYPES.stream();
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, "牛奶", null, null, null, null, null, null,
                null, null, null, null, null);
    }
}

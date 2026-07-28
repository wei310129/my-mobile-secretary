package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates a persistent item under the current actor/workspace before focus activation. */
@Component
public final class ItemConversationFocusContributor implements ConversationFocusContributor {

    private static final String ITEM_PREFIX = "item:";
    private final ItemService items;

    public ItemConversationFocusContributor(ItemService items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    @Override
    public String rootDomain() {
        return "ITEM";
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        Long id = TaskConversationFocusContributor.resourceId(target, ITEM_PREFIX);
        return id != null && items.isAvailableForFocus(id, target.safeLabel());
    }
}

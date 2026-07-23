package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Java, rather than the user-facing text catalog, owns executable focus classification. */
@Component
public final class ConversationFocusCapabilityCatalog {

    private final Map<IntentCommand.Type, FocusBehavior> behaviors;

    public ConversationFocusCapabilityCatalog() {
        this(defaultBehaviors());
    }

    ConversationFocusCapabilityCatalog(Map<IntentCommand.Type, FocusBehavior> behaviors) {
        EnumMap<IntentCommand.Type, FocusBehavior> copy = new EnumMap<>(IntentCommand.Type.class);
        copy.putAll(Objects.requireNonNull(behaviors, "behaviors"));
        for (IntentCommand.Type type : IntentCommand.Type.values()) {
            if (copy.get(type) == null) {
                throw new IllegalStateException("missing focus behavior for intent " + type);
            }
        }
        this.behaviors = Map.copyOf(copy);
    }

    public FocusBehavior behaviorFor(IntentCommand.Type type) {
        FocusBehavior behavior = behaviors.get(Objects.requireNonNull(type, "type"));
        if (behavior == null) {
            throw new IllegalStateException("missing focus behavior for intent " + type);
        }
        return behavior;
    }

    public Map<IntentCommand.Type, FocusBehavior> behaviors() {
        return behaviors;
    }

    private static Map<IntentCommand.Type, FocusBehavior> defaultBehaviors() {
        EnumMap<IntentCommand.Type, FocusBehavior> values = new EnumMap<>(IntentCommand.Type.class);
        for (IntentCommand.Type type : IntentCommand.Type.values()) {
            values.put(type, FocusBehavior.ONE_SHOT_KEEP);
        }
        values.put(IntentCommand.Type.UNKNOWN, FocusBehavior.NEVER_TOUCH);
        values.put(IntentCommand.Type.SOCIAL, FocusBehavior.NEVER_TOUCH);
        values.put(IntentCommand.Type.FEEDBACK, FocusBehavior.NEVER_TOUCH);
        values.put(IntentCommand.Type.EXPLAIN_LAST_FAILURE, FocusBehavior.NEVER_TOUCH);
        values.put(IntentCommand.Type.EXIT_CONVERSATION_FOCUS, FocusBehavior.CONTROL);
        values.put(IntentCommand.Type.CLOSE_CONVERSATION_FOCUS, FocusBehavior.CONTROL);
        values.put(IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE, FocusBehavior.CONTROL);
        values.put(IntentCommand.Type.CREATE_PROJECT, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.OPEN_PROJECT_EDIT_MODE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.SWITCH_PROJECT_EDIT_MODE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.RESUME_PROJECT_EDIT_MODE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.SHOW_PROJECT_OVERVIEW, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.COMPLETE_PROJECT, FocusBehavior.ONE_SHOT_KEEP);
        values.put(IntentCommand.Type.REOPEN_PROJECT, FocusBehavior.ONE_SHOT_KEEP);
        values.put(IntentCommand.Type.ARCHIVE_PROJECT, FocusBehavior.TERMINAL);
        values.put(IntentCommand.Type.ASK_TASK_INFO, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.CREATE_TASK, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.CREATE_SCHEDULE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.RESCHEDULE_SCHEDULE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.UPDATE_TASK, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.RECORD_VENUE_VISIT_INFO, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.UPSERT_TAG_RELATION, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.ASK_TAGGED_RECORDS, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.PLAN_TRIP, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.PLAN_PACKING_LIST, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.CANCEL_SCHEDULE, FocusBehavior.TERMINAL);
        values.put(IntentCommand.Type.CANCEL_TASK, FocusBehavior.TERMINAL);
        values.put(IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT, FocusBehavior.TERMINAL);
        values.put(IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT, FocusBehavior.TERMINAL);
        values.put(IntentCommand.Type.ADD_SHOPPING_ITEMS, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.MARK_SHOPPING_PURCHASED, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.REMOVE_SHOPPING_ITEM, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.SET_INVENTORY, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.ADJUST_INVENTORY, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.BIND_ITEM_PLACE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.ASK_ITEM_PLACES, FocusBehavior.START_OR_SWITCH);
        return values;
    }
}

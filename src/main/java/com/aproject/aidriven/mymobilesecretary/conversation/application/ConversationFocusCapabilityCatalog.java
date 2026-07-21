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
        values.put(IntentCommand.Type.CREATE_TASK, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.CREATE_SCHEDULE, FocusBehavior.START_OR_SWITCH);
        values.put(IntentCommand.Type.RESCHEDULE_SCHEDULE, FocusBehavior.START_OR_SWITCH);
        return values;
    }
}

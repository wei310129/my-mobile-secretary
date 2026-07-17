package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named context fragment required by a capability. It is intentionally an extensible value
 * object rather than an enum so a new feature does not require changing a central type.
 */
public record ContextRequirement(String key) implements Comparable<ContextRequirement> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_LENGTH = 100;

    public static final ContextRequirement CONVERSATION_HISTORY = of("conversation.history");
    public static final ContextRequirement OPEN_TASKS = of("task.open");
    public static final ContextRequirement SCHEDULE_WINDOW = of("schedule.window");
    public static final ContextRequirement PLACES = of("place.relevant");
    public static final ContextRequirement ITEMS = of("inventory.relevant");
    public static final ContextRequirement PRICE_HISTORY = of("knowledge.price_history");
    public static final ContextRequirement PREFERENCES = of("user.preferences");
    public static final ContextRequirement LAST_INTENT_FAILURE = of("intent.last_failure");

    public ContextRequirement {
        Objects.requireNonNull(key, "key");
        if (key.isBlank() || key.length() > MAX_LENGTH || !FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "context requirement must be 1-100 lowercase characters separated by '.', '_' or '-': " + key);
        }
    }

    public static ContextRequirement of(String key) {
        return new ContextRequirement(key);
    }

    @Override
    public int compareTo(ContextRequirement other) {
        return key.compareTo(other.key);
    }

    @Override
    public String toString() {
        return key;
    }
}

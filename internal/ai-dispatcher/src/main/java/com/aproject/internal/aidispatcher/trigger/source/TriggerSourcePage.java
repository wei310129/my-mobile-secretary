package com.aproject.internal.aidispatcher.trigger.source;

import com.aproject.internal.aidispatcher.trigger.domain.DevelopmentTriggerEvent;
import java.util.List;

public record TriggerSourcePage(
        String nextCursor,
        boolean hasMore,
        List<DevelopmentTriggerEvent> events
) {
    public TriggerSourcePage {
        events = events == null ? List.of() : List.copyOf(events);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("A paginated trigger page requires nextCursor");
        }
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
    }
}

package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DevelopmentFeedPage(
        List<Event> events,
        String nextCursor,
        boolean hasMore
) {
    public DevelopmentFeedPage {
        events = List.copyOf(events);
    }

    public record Event(
            String eventId,
            String type,
            Instant occurredAt,
            String subjectRef,
            int schemaVersion,
            Map<String, Object> metadata
    ) {
        public Event {
            metadata = Map.copyOf(metadata);
        }
    }
}

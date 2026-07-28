package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.util.List;
import java.util.Objects;

public record CalendarActivityDraft(
        String title,
        CalendarPlacement placement,
        String category,
        List<CalendarNodeDraft> nodes) {

    public CalendarActivityDraft {
        Objects.requireNonNull(placement, "placement");
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }
}

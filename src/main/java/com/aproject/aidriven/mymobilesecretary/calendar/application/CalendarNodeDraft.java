package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import java.util.Objects;

public record CalendarNodeDraft(CalendarTimeNode node) {

    public CalendarNodeDraft {
        Objects.requireNonNull(node, "node");
    }

    public static CalendarNodeDraft of(CalendarTimeNode node) {
        return new CalendarNodeDraft(node);
    }
}

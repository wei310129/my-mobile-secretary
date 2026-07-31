package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import java.util.Objects;

public record CalendarNodeDraft(CalendarTimeNode node, CalendarLocation location) {

    public CalendarNodeDraft {
        Objects.requireNonNull(node, "node");
    }

    public CalendarNodeDraft(CalendarTimeNode node) {
        this(node, null);
    }

    public static CalendarNodeDraft of(CalendarTimeNode node) {
        return new CalendarNodeDraft(node, null);
    }

    public static CalendarNodeDraft at(
            CalendarTimeNode node, CalendarLocation location) {
        return new CalendarNodeDraft(node, Objects.requireNonNull(location, "location"));
    }
}
